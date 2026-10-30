package br.com.el.branchcontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;

public class GitRunner {
    private static final ExecutorService READERS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "git-output");
        thread.setDaemon(true);
        return thread;
    });
    private final int timeoutSeconds;
    private final Runnable preflight;
    private final java.util.Set<Process> active = ConcurrentHashMap.newKeySet();
    public void abortActive() { active.forEach(GitRunner::terminate); }

    public GitRunner(int timeoutSeconds) { this(timeoutSeconds,()->{}); }
    public GitRunner(int timeoutSeconds,Runnable preflight) { this.timeoutSeconds = Math.max(timeoutSeconds, 5); this.preflight=preflight; }

    public record Result(int code, String output, String error) {
        public String combined() { return output + (error.isBlank() ? "" : "\n" + error); }
    }

    public Result run(Path repo, String... args) { return run(repo, Map.of(), args); }

    /** Native Git-to-Git pipe: only the final output is buffered in Java. */
    public Result requirePipeline(Path repo, List<String> producer, List<String> consumer) {
        preflight.run();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        List<Process> processes = List.of();
        var readers = new ArrayList<Future<byte[]>>();
        try {
            processes = ProcessBuilder.startPipeline(List.of(builder(repo, producer), builder(repo, consumer)));
            active.addAll(processes);
            // Register both before rechecking the lease, including loss during startup.
            preflight.run();
            Process last = processes.get(1);
            Future<byte[]> stdout = READERS.submit(() -> readBounded(last.getInputStream()));
            readers.add(stdout);
            for (Process process : processes) readers.add(READERS.submit(() -> readBounded(process.getErrorStream())));
            for (Process process : processes) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    throw new AppException(408, "O Git excedeu " + timeoutSeconds + " segundos. Confira o estado do repositório antes de tentar novamente.");
                }
            }
            var output = new ArrayList<String>();
            for (Future<byte[]> reader : readers) {
                output.add(sanitize(new String(reader.get(Math.max(1, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS), StandardCharsets.UTF_8)));
            }
            int code = processes.stream().mapToInt(Process::exitValue).filter(value -> value != 0).findFirst().orElse(0);
            Result result = new Result(code, output.get(0), output.get(1) + output.get(2));
            if (code != 0) throw new AppException(422, "Git não concluiu a operação: " + sanitize(result.combined()).strip());
            return result;
        } catch (AppException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(503, "Operação interrompida. Confira o estado do Git.");
        } catch (IOException ex) {
            throw new AppException(500, "Não foi possível iniciar o Git. Confira se git.exe está no PATH.");
        } catch (ExecutionException | TimeoutException ex) {
            throw new AppException(422, "Não foi possível ler a saída do Git, ou ela excedeu o limite de 8 MB.");
        } finally {
            for (Process process : processes) {
                if (process.isAlive()) terminate(process);
                active.remove(process);
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
            readers.forEach(reader -> reader.cancel(true));
        }
    }

    private static ProcessBuilder builder(Path repo, List<String> arguments) {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-c",
                "i18n.logOutputEncoding=utf-8", "-C", repo.toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GCM_INTERACTIVE", "Never");
        builder.environment().put("LC_ALL", "C");
        return builder;
    }

    public Result require(Path repo, String... args) { return require(repo, Map.of(), args); }

    public Result require(Path repo, Map<String, String> environment, String... args) {
        Result result = run(repo, environment, args);
        if (result.code() != 0) {
            throw new AppException(422, "Git não concluiu a operação: " + sanitize(result.combined()).strip());
        }
        return result;
    }

    public Result run(Path repo, Map<String, String> environment, String... args) {
        preflight.run();
        var command = new ArrayList<String>();
        command.addAll(Arrays.asList("git", "-c", "core.quotepath=false", "-c",
                "i18n.logOutputEncoding=utf-8", "-C", repo.toString()));
        command.addAll(Arrays.asList(args));
        Process process = null;
        Future<byte[]> stdout = null;
        Future<byte[]> stderr = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "Never");
            builder.environment().put("LC_ALL", "C");
            builder.environment().putAll(environment);
            process = builder.start();
            active.add(process);
            // Close the race between the first preflight and starting the process.
            preflight.run();
            Process started = process;
            stdout = READERS.submit(() -> readBounded(started.getInputStream()));
            stderr = READERS.submit(() -> readBounded(started.getErrorStream()));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                throw new AppException(408, "O Git excedeu " + timeoutSeconds + " segundos. Confira o estado do repositório antes de tentar novamente.");
            }
            return new Result(process.exitValue(),
                    sanitize(new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8)),
                    sanitize(new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8)));
        } catch (AppException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(503, "Operação interrompida. Confira o estado do Git.");
        } catch (IOException ex) {
            throw new AppException(500, "Não foi possível iniciar o Git. Confira se git.exe está no PATH.");
        } catch (ExecutionException | TimeoutException ex) {
            throw new AppException(422, "Não foi possível ler a saída do Git, ou ela excedeu o limite de 8 MB.");
        } finally {
            if (process != null && process.isAlive()) terminate(process);
            if (process != null) active.remove(process);
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
        }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Saída maior que 8 MB.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String sanitize(String text) {
        return text.replaceAll("(https?://)[^\\s/@]+:[^\\s/@]+@", "$1***@");
    }
}
