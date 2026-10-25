package io.github.arikregev.reach.engine.java;

import io.github.arikregev.reach.engine.BuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a build tool with its output in a log file, and kills the whole process tree on timeout. */
final class Processes {

    private static final int TAIL_LINES = 25;

    private Processes() {
    }

    static void run(List<String> command, Path dir, Map<String, String> env, Path log, Duration timeout)
            throws BuildException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().putAll(env);
        Process p;
        try {
            Files.createDirectories(log.getParent());
            p = pb.start();
        } catch (IOException e) {
            throw new BuildException(command.getFirst() + " could not be started: " + e.getMessage(), e);
        }
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(p);
                throw new BuildException(command.getFirst() + " did not finish within " + timeout + tail(log));
            }
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            throw new BuildException(command.getFirst() + " was interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new BuildException(command.getFirst() + " exited with " + p.exitValue() + tail(log));
        }
    }

    /** The first line a command prints, e.g. its version; empty if it fails. */
    static String firstLine(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("Picked up") && !l.startsWith("---"))
                    .findFirst().orElse("");
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String tail(Path log) {
        try {
            // Stack frames push the actual cause out of the tail, so they are left out.
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.stripLeading().startsWith("at ") && !l.stripLeading().startsWith("... ")).toList();
            return ":\n" + String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
