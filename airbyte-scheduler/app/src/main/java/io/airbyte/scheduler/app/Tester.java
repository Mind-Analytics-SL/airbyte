package io.airbyte.scheduler.app;

import com.google.common.collect.ImmutableMap;
import io.airbyte.scheduler.app.kube.WorkerHeartbeatServer;
import io.airbyte.workers.WorkerException;
import io.airbyte.workers.process.KubeProcessFactory;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

public class Tester {
    public static void main(String[] args) throws Exception {
        System.out.println("testing...");

        WorkerHeartbeatServer server = new WorkerHeartbeatServer(4000);
        server.startBackground();

        final KubernetesClient kubeClient = new DefaultKubernetesClient();
        final BlockingQueue<Integer> workerPorts = new LinkedBlockingDeque<>(List.of(4001, 4002, 4003));
        KubeProcessFactory processFactory = new KubeProcessFactory("default", kubeClient, 4000, workerPorts);

        Process process = processFactory.create(
                "some-id",
                0,
                Path.of("/tmp/job-root"),
                "airbyte/source-exchange-rates:0.2.3",
                false,
                ImmutableMap.of(),
                "while true; do echo hi; sleep 1; done");
        System.out.println("sleeping...");
        Thread.sleep(5000);

        System.out.println("shutting down server...");
        server.stop();

        System.out.println("waiting for process...");
        process.waitFor();

//        Process process = processFactory.create(
//                "some-id",
//                0,
//                Path.of("/tmp/job-root"),
//                "airbyte/source-exchange-rates:0.2.3",
//                false,
//                ImmutableMap.of(),
//                "python /airbyte/integration_code/main.py",
//                "spec");
//
//        System.out.println("waiting for process...");
//        process.waitFor();
//
//        System.out.println("shutting down server...");
//        server.stop();

        System.out.println("done!");
    }
}
