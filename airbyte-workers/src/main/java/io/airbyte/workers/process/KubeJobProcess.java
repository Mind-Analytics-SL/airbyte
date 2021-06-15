/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.workers.process;

import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.string.Strings;
import io.airbyte.workers.WorkerException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.Job;
import io.fabric8.kubernetes.api.model.batch.JobBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import org.apache.commons.io.output.NullOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A Process abstraction backed by a Kube Pod running in a Kubernetes cluster 'somewhere'. The
 * parent process starting a Kube Pod Process needs to exist within the Kube networking space. This
 * is so the parent process can forward data into the child's stdin and read the child's stdout and
 * stderr streams and copy configuration files over.
 *
 * This is made possible by:
 * <li>1) An init container that creates 3 named pipes corresponding to stdin, stdout and std err on
 * a shared volume.</li>
 * <li>2) Config files (e.g. config.json, catalog.json etc) are copied from the parent process into
 * a shared volume.</li>
 * <li>3) Redirecting the stdin named pipe to the original image's entrypoint and it's output into
 * the respective named pipes for stdout and stderr.</li>
 * <li>4) Each named pipe has a corresponding side car. Each side car forwards its stream
 * accordingly using socat. e.g. stderr/stdout is forwarded to parent process while input from the
 * parent process is forwarded into stdin.</li>
 * <li>5) The parent process listens on the stdout and stederr sockets for an incoming TCP
 * connection. It also initiates a TCP connection to the child process aka the Kube pod on the
 * specified stdin socket.</li>
 * <li>6) The child process is able to access configuration data via the shared volume. It's inputs
 * and outputs - stdin, stdout and stderr - are forwarded the parent process via the sidecars.</li>
 *
 *
 * See the constructor for more information.
 */

// todo: revert change to make this a job-based process
// TODO(Davin): Better test for this. See https://github.com/airbytehq/airbyte/issues/3700.
public class KubeJobProcess extends Process {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubeJobProcess.class);

  private static final String INIT_CONTAINER_NAME = "init";

  private static final String PIPES_DIR = "/pipes";
  private static final String STDIN_PIPE_FILE = PIPES_DIR + "/stdin";
  private static final String STDOUT_PIPE_FILE = PIPES_DIR + "/stdout";
  private static final String STDERR_PIPE_FILE = PIPES_DIR + "/stderr";
  private static final String CONFIG_DIR = "/config";
  private static final String TERMINATION_DIR = "/termination";
  private static final String TERMINATION_FILE_MAIN = TERMINATION_DIR + "/main";
  private static final String TERMINATION_FILE_CHECK = TERMINATION_DIR + "/check";
  private static final String SUCCESS_FILE_NAME = "FINISHED_UPLOADING";

  // 143 is the typical SIGTERM exit code.
  private static final int KILLED_EXIT_CODE = 143;
  private static final int STDIN_REMOTE_PORT = 9001;

  private final KubernetesClient client;
  private final Pod podDefinition;
  // Necessary since it is not possible to retrieve the pod's actual exit code upon termination. This
  // is because the Kube API server does not keep
  // terminated pod history like it does for successful pods.
  // This variable should be set in functions where the pod is forcefully terminated. See
  // getReturnCode() for more info.
  private final AtomicBoolean wasKilled = new AtomicBoolean(false);

  private final OutputStream stdin;
  private InputStream stdout;
  private InputStream stderr;

  private final Consumer<Integer> portReleaser;
  private final ServerSocket stdoutServerSocket;
  private final int stdoutLocalPort;
  private final ServerSocket stderrServerSocket;
  private final int stderrLocalPort;
  private final ExecutorService executorService;

  // TODO(Davin): Cache this result.
  public static String getCommandFromImage(KubernetesClient client, String imageName, String namespace) throws InterruptedException {
    final String podName = Strings.addRandomSuffix("airbyte-command-fetcher", "-", 5);

    Container commandFetcher = new ContainerBuilder()
        .withName("airbyte-command-fetcher")
        .withImage(imageName)
        .withCommand("sh", "-c", "echo \"AIRBYTE_ENTRYPOINT=$AIRBYTE_ENTRYPOINT\"")
        .build();

    Pod pod = new PodBuilder()
        .withApiVersion("v1")
        .withNewMetadata()
        .withName(podName)
        .endMetadata()
        .withNewSpec()
        .withRestartPolicy("Never")
        .withContainers(commandFetcher)
        .endSpec()
        .build();
    LOGGER.info("Creating pod...");
    Pod podDefinition = client.pods().inNamespace(namespace).createOrReplace(pod);
    LOGGER.info("Waiting until command fetcher pod completes...");
    // TODO(Davin): If a pod is missing, this will wait for up to 2 minutes before error-ing out.
    // Figure out a better way.
    client.resource(podDefinition).waitUntilCondition(p -> p.getStatus().getPhase().equals("Succeeded"), 2, TimeUnit.MINUTES);

    var logs = client.pods().inNamespace(namespace).withName(podName).getLog();
    if (!logs.contains("AIRBYTE_ENTRYPOINT")) {
      throw new RuntimeException(
          "Missing AIRBYTE_ENTRYPOINT from command fetcher logs. This should not happen. Check the echo command has not been changed.");
    }

    var envVal = logs.split("=")[1].strip();
    if (envVal.isEmpty()) {
      throw new RuntimeException("No AIRBYTE_ENTRYPOINT environment variable found. Connectors must have this set in order to run on Kubernetes.");
    }

    return envVal;
  }

  public static String getPodIP(KubernetesClient client, String podName, String namespace) {
    var pod = client.pods().inNamespace(namespace).withName(podName).get();
    if (pod == null) {
      throw new RuntimeException("Error: unable to find pod!");
    }
    return pod.getStatus().getPodIP();
  }

  private static Container getInit(boolean usesStdin, List<VolumeMount> mainVolumeMounts) {
    var initEntrypointStr = String.format("mkfifo %s && mkfifo %s", STDOUT_PIPE_FILE, STDERR_PIPE_FILE);

    if (usesStdin) {
      initEntrypointStr = String.format("mkfifo %s && ", STDIN_PIPE_FILE) + initEntrypointStr;
    }

    initEntrypointStr = initEntrypointStr + String.format(" && until [ -f %s ]; do sleep 5; done;", SUCCESS_FILE_NAME);

    return new ContainerBuilder()
        .withName(INIT_CONTAINER_NAME)
        .withImage("busybox:1.28")
        .withWorkingDir(CONFIG_DIR)
        .withCommand("sh", "-c", initEntrypointStr)
        .withVolumeMounts(mainVolumeMounts)
        .build();
  }

  private static Container getMain(String image, boolean usesStdin, String entrypoint, List<VolumeMount> mainVolumeMounts, String[] args) {
    var argsStr = String.join(" ", args);
    var entrypointStr = entrypoint + " " + argsStr;
    var trap = "trap \"touch " + TERMINATION_FILE_MAIN + "\" EXIT\n";

    var entrypointStrWithPipes = trap + entrypointStr + String.format(" 2> %s > %s", STDERR_PIPE_FILE, STDOUT_PIPE_FILE);
    if (usesStdin) {
      entrypointStrWithPipes = String.format("cat %s | ", STDIN_PIPE_FILE) + entrypointStrWithPipes;
    }

    return new ContainerBuilder()
        .withName("main")
        .withImage(image)
        .withCommand("sh", "-c", entrypointStrWithPipes)
        .withWorkingDir(CONFIG_DIR)
        .withVolumeMounts(mainVolumeMounts)
        .build();
  }

  private static void copyFilesToKubeConfigVolume(KubernetesClient client, String podName, String namespace, Map<String, String> files) {
    List<Map.Entry<String, String>> fileEntries = new ArrayList<>(files.entrySet());

    for (Map.Entry<String, String> file : fileEntries) {
      Path tmpFile = null;
      try {
        tmpFile = Path.of(IOs.writeFileToRandomTmpDir(file.getKey(), file.getValue()));

        LOGGER.info("Uploading file: " + file.getKey());

        System.out.println("podName = " + podName);

        client.pods().inNamespace(namespace).withName(podName).inContainer(INIT_CONTAINER_NAME)
            .file(CONFIG_DIR + "/" + file.getKey())
            .upload(tmpFile);

      } finally {
        if (tmpFile != null) {
          tmpFile.toFile().delete();
        }
      }
    }
  }

  /**
   * The calls in this function aren't straight-forward due to api limitations. There is no proper way
   * to directly look for containers within a pod or query if a container is in a running state beside
   * checking if the getRunning field is set. We could put this behind an interface, but that seems
   * heavy-handed compared to the 10 lines here.
   */
  private static void waitForInitPodToRun(KubernetesClient client, Pod podDefinition) throws InterruptedException {
    LOGGER.info("Waiting for init container to be ready before copying files...");
    client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName())
        .waitUntilCondition(p -> p.getStatus().getInitContainerStatuses().size() != 0, 5, TimeUnit.MINUTES);
    LOGGER.info("Init container present..");
    client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName())
        .waitUntilCondition(p -> p.getStatus().getInitContainerStatuses().get(0).getState().getRunning() != null, 5, TimeUnit.MINUTES);
    LOGGER.info("Init container ready..");
  }

  public KubeJobProcess(KubernetesClient client,
                        Consumer<Integer> portReleaser,
                        String podName,
                        String namespace,
                        String image,
                        int stdoutLocalPort,
                        int stderrLocalPort,
                        int heartbeatPort,
                        boolean usesStdin,
                        final Map<String, String> files,
                        final String entrypointOverride,
                        final String... args)
      throws IOException, InterruptedException {
    this.client = client;
    this.portReleaser = portReleaser;
    this.stdoutLocalPort = stdoutLocalPort;
    this.stderrLocalPort = stderrLocalPort;

    stdoutServerSocket = new ServerSocket(stdoutLocalPort);
    stderrServerSocket = new ServerSocket(stderrLocalPort);
    executorService = Executors.newFixedThreadPool(2);
    setupStdOutAndStdErrListeners();

    String entrypoint = entrypointOverride == null ? getCommandFromImage(client, image, namespace) : entrypointOverride;
    LOGGER.info("Found entrypoint: {}", entrypoint);

    Volume pipeVolume = new VolumeBuilder()
        .withName("airbyte-pipes")
        .withNewEmptyDir()
        .endEmptyDir()
        .build();

    VolumeMount pipeVolumeMount = new VolumeMountBuilder()
        .withName("airbyte-pipes")
        .withMountPath(PIPES_DIR)
        .build();

    Volume configVolume = new VolumeBuilder()
        .withName("airbyte-config")
        .withNewEmptyDir()
        .endEmptyDir()
        .build();

    VolumeMount configVolumeMount = new VolumeMountBuilder()
        .withName("airbyte-config")
        .withMountPath(CONFIG_DIR)
        .build();

      Volume terminationVolume = new VolumeBuilder()
              .withName("airbyte-termination")
              .withNewEmptyDir()
              .endEmptyDir()
              .build();

      VolumeMount terminationVolumeMount = new VolumeMountBuilder()
              .withName("airbyte-termination")
              .withMountPath(TERMINATION_DIR)
              .build();

    Container init = getInit(usesStdin, List.of(pipeVolumeMount, configVolumeMount));
    Container main = getMain(image, usesStdin, entrypoint, List.of(pipeVolumeMount, configVolumeMount, terminationVolumeMount), args);

    final String remoteStdinCommand = wrapWithHappyFileCloser("socat -d -d -d TCP-L:9001 STDOUT > " + STDIN_PIPE_FILE, TERMINATION_FILE_MAIN);
    Container remoteStdin = new ContainerBuilder()
        .withName("remote-stdin")
        .withImage("alpine/socat:1.7.4.1-r1")
        .withCommand("sh", "-c", "socat -d -d -d TCP-L:9001 STDOUT > " + STDIN_PIPE_FILE)
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .build();

    var localIp = InetAddress.getLocalHost().getHostAddress();
    String relayStdoutCommand = wrapWithHappyFileCloser(String.format("cat %s | socat -d -d -d - TCP:%s:%s", STDOUT_PIPE_FILE, localIp, stdoutLocalPort), TERMINATION_FILE_MAIN);
    Container relayStdout = new ContainerBuilder()
        .withName("relay-stdout")
        .withImage("alpine/socat:1.7.4.1-r1")
        .withCommand("sh", "-c", String.format("cat %s | socat -d -d -d - TCP:%s:%s", STDOUT_PIPE_FILE, localIp, stdoutLocalPort))
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .build();

    String relayStderrCommand = wrapWithHappyFileCloser(String.format("cat %s | socat -d -d -d - TCP:%s:%s", STDERR_PIPE_FILE, localIp, stderrLocalPort), TERMINATION_FILE_MAIN);
    Container relayStderr = new ContainerBuilder()
        .withName("relay-stderr")
        .withImage("alpine/socat:1.7.4.1-r1")
        .withCommand("sh", "-c", String.format("cat %s | socat -d -d -d - TCP:%s:%s", STDERR_PIPE_FILE, localIp, stderrLocalPort))
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .build();

    final String heartbeatUrl = "host.docker.internal:" + heartbeatPort; // todo: switch back to: localIp + ":" + heartbeatPort;
    final String heartbeatCommand = wrapWithSadFileCloser("set -e; while true; do curl " + heartbeatUrl + "; sleep 1; done", TERMINATION_FILE_MAIN);
    System.out.println("heartbeatCommand = " + heartbeatCommand);
    Container callHeartbeatServer = new ContainerBuilder()
            .withName("call-heartbeat-server")
            .withImage("curlimages/curl:7.77.0")
            .withCommand("sh")
            .withArgs("-c", heartbeatCommand) // todo: kill this container when other pods stop so it's not stuck in notready
            .withVolumeMounts(terminationVolumeMount)
            .build();

    List<Container> containers = usesStdin ? List.of(main, remoteStdin, relayStdout, relayStderr, callHeartbeatServer) : List.of(main, relayStdout, relayStderr, callHeartbeatServer);

    final Job job = new JobBuilder()
            .withApiVersion("batch/v1")
            .withNewMetadata()
            .withName("job-" + podName) // todo: figure out how naming works here
            .endMetadata()
            .withNewSpec()
//            .withCompletions(3) // one for each non-check container? failure still should be caught for check container
            // todo: add ttlSecondsAfterFinished for cleaning up old jobs
            .withNewTemplate()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withInitContainers(init)
            .withContainers(containers)
            .withVolumes(pipeVolume, configVolume, terminationVolume)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build();

    LOGGER.info("Creating pod...");
    final Job jobDefinition = client.batch().jobs().inNamespace(namespace).createOrReplace(job);

    // todo: does this need to wait?
    final PodList podList = client.pods().inNamespace(namespace).withLabel("job-name", jobDefinition.getMetadata().getName()).list();

    this.podDefinition = podList.getItems().get(0);

    // todo: get podDefinition
    waitForInitPodToRun(client, podDefinition);

    LOGGER.info("Copying files...");
    Map<String, String> filesWithSuccess = new HashMap<>(files);

    // We always copy the empty success file to ensure our waiting step can detect the init container in
    // RUNNING. Otherwise, the container can complete and exit before we are able to detect it.
    filesWithSuccess.put(SUCCESS_FILE_NAME, "");
    copyFilesToKubeConfigVolume(client, podDefinition.getMetadata().getName(), namespace, filesWithSuccess);

    LOGGER.info("Waiting until pod is ready...");
    client.resource(podDefinition).waitUntilCondition(p -> {
      boolean isReady = Objects.nonNull(p) && Readiness.getInstance().isReady(p);
      return isReady || isTerminal(p); // todo: fixes too fast completion, but what do we do for failure/error?
    }, 10, TimeUnit.DAYS);

    // allow writing stdin to pod
    LOGGER.info("Reading pod IP...");
    var podIp = getPodIP(client, podDefinition.getMetadata().getName(), namespace);
    LOGGER.info("Pod IP: {}", podIp);

    if (usesStdin) {
      LOGGER.info("Creating stdin socket...");
      var socketToDestStdIo = new Socket(podIp, STDIN_REMOTE_PORT);
      this.stdin = socketToDestStdIo.getOutputStream();
    } else {
      LOGGER.info("Using null stdin output stream...");
      this.stdin = NullOutputStream.NULL_OUTPUT_STREAM;
    }
  }

  private static String wrapWithHappyFileCloser(String command, String file) {
    return "(" + command + ") &\nCHILD_PID=$!\n(while true; do if [[ -f " + file + "]]; then kill $CHILD_PID; fi; sleep 1; done) &\nwait $CHILD_PID\nif [[ -f " + file + " ]]; then exit 0; fi";
  }

  private static String wrapWithSadFileCloser(String command, String file) {
    return "(" + command + ") &\nCHILD_PID=$!\n(while true; do if [[ -f " + file + "]]; then exit 0; fi; sleep 1; done) &\nwait $CHILD_PID\nexit 1";
  }

  private void setupStdOutAndStdErrListeners() {
    executorService.submit(() -> {
      try {
        LOGGER.info("Creating stdout socket server...");
        var socket = stdoutServerSocket.accept(); // blocks until connected
        LOGGER.info("Setting stdout...");
        this.stdout = socket.getInputStream();
      } catch (IOException e) {
        e.printStackTrace(); // todo: propagate exception / join at the end of constructor
      }
    });
    executorService.submit(() -> {
      try {
        LOGGER.info("Creating stderr socket server...");
        var socket = stderrServerSocket.accept(); // blocks until connected
        LOGGER.info("Setting stderr...");
        this.stderr = socket.getInputStream();
      } catch (IOException e) {
        e.printStackTrace(); // todo: propagate exception / join at the end of constructor
      }
    });
  }

  @Override
  public OutputStream getOutputStream() {
    return this.stdin;
  }

  @Override
  public InputStream getInputStream() {
    return this.stdout;
  }

  @Override
  public InputStream getErrorStream() {
    return this.stderr;
  }

  /**
   * Immediately terminates the Kube Pod backing this process and cleans up IO resources.
   */
  @Override
  public int waitFor() throws InterruptedException {
    try {
      Pod refreshedPod = client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName()).get();
      client.resource(refreshedPod).waitUntilCondition(this::isTerminal, 10, TimeUnit.DAYS);
      wasKilled.set(true);
      return exitValue();
    } finally {
      close();
    }
  }

  /**
   * Intended to gracefully clean up after a completed Kube Pod. This should only be called if the
   * process is successful.
   */
  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    try {
      return super.waitFor(timeout, unit);
    } finally {
      close();
    }
  }

  /**
   * Immediately terminates the Kube Pod backing this process and cleans up IO resources.
   */
  @Override
  public void destroy() {
    LOGGER.info("Destroying Kube process: {}", podDefinition.getMetadata().getName());
    try {
      client.resource(podDefinition).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
      wasKilled.set(true);
    } finally {
      close();
      LOGGER.info("Destroyed Kube process: {}", podDefinition.getMetadata().getName());
    }
  }

  /**
   * Close all open resource in the opposite order of resource creation.
   */
  private void close() {
    Exceptions.swallow(this.stdin::close);
    Exceptions.swallow(this.stdout::close);
    Exceptions.swallow(this.stderr::close);
    Exceptions.swallow(this.stdoutServerSocket::close);
    Exceptions.swallow(this.stderrServerSocket::close);
    Exceptions.swallow(this.executorService::shutdownNow);
    Exceptions.swallow(() -> portReleaser.accept(stdoutLocalPort));
    Exceptions.swallow(() -> portReleaser.accept(stderrLocalPort));
  }

  private boolean isTerminal(Pod pod) {
    if (pod.getStatus() != null) {
      return pod.getStatus()
          .getContainerStatuses()
          .stream()
          .anyMatch(e -> e.getState() != null && e.getState().getTerminated() != null);
    } else {
      return false;
    }
  }

  private int getReturnCode(Pod pod) {
    var name = pod.getMetadata().getName();
    Pod refreshedPod = client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(name).get();
    if (refreshedPod == null) {
      if (wasKilled.get()) {
        LOGGER.info("Unable to find pod {} to retrieve exit value. Defaulting to  value {}. This is expected if the job was cancelled.", name,
            KILLED_EXIT_CODE);
        return KILLED_EXIT_CODE;
      }
      // If the pod cannot be found and was not killed, it either means 1) the pod was not created
      // properly 2) this method is incorrectly called.
      throw new RuntimeException("Cannot find pod while trying to retrieve exit code. This probably means the Pod was not correctly created.");
    }
    if (!isTerminal(refreshedPod)) {
      throw new IllegalThreadStateException("Kube pod process has not exited yet.");
    }

    return refreshedPod.getStatus().getContainerStatuses()
        .stream()
        .filter(containerStatus -> containerStatus.getState() != null && containerStatus.getState().getTerminated() != null)
        .map(containerStatus -> {
          int statusCode = containerStatus.getState().getTerminated().getExitCode();
          LOGGER.info("Exit code for pod {}, container {} is {}", name, containerStatus.getName(), statusCode);
          return statusCode;
        })
        .reduce(Integer::sum)
        .orElseThrow();
  }

  @Override
  public int exitValue() {
    return getReturnCode(podDefinition);
  }

}
