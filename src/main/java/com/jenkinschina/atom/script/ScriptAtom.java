package com.jenkinschina.atom.script;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Shell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one script with the interpreter of the caller's choice.
 *
 * <p>Everything that makes running a script hard -- locating the interpreter, getting the script to it without having
 * quoting rewritten, timing out, deciding what a non-zero exit means -- lives in {@link Shell.Script}, so this atom is
 * parameter mapping and an honest report of what happened. That is deliberate: six interpreters, six atoms would mean
 * six copies of the same failure modes.
 */
public class ScriptAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Shell.Interpreter interpreter;
        try {
            interpreter = Shell.Interpreter.of(context.getParam("interpreter", "sh"));
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40001, e.getMessage());
        }

        String text = context.getParam("script", "");
        String fileParam = context.getParam("scriptFile", "").trim();
        Path workspace = context.getWorkspace();
        Path scriptFile = null;
        if (text.isBlank()) {
            if (fileParam.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40002, "script or scriptFile is required");
            }
            scriptFile = workspace.resolve(fileParam);
            if (!Files.isRegularFile(scriptFile)) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "no such script file in the workspace: " + fileParam);
            }
        }

        Path workdir = null;
        String workdirParam = context.getParam("workdir", "").trim();
        if (!workdirParam.isEmpty()) {
            workdir = workspace.resolve(workdirParam);
            if (!Files.isDirectory(workdir)) {
                return AtomResult.error(ErrorType.USER, 40004, "no such workdir in the workspace: " + workdirParam);
            }
        }

        boolean failOnError = context.getBooleanParam("failOnError", true);
        int timeoutSeconds = context.getIntParam("timeoutSeconds", 1800);
        Map<String, String> env = parseEnv(context.getParam("env", ""));
        List<String> args = parseArgs(context.getParam("args", ""));

        Shell.Script.Builder spec = Shell.Script.builder()
                .interpreter(interpreter)
                .interpreterPath(context.getParam("interpreterPath", ""))
                .args(args)
                .env(env)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .failOnError(false);           // this atom decides what a non-zero exit means
        if (scriptFile != null) {
            spec.file(scriptFile);
        } else {
            spec.script(text);
        }
        if (workdir != null) {
            spec.workdir(workdir);
        }

        Shell.Result result;
        try {
            result = Shell.run(spec.build());
        } catch (IllegalStateException e) {
            // the interpreter could not be located: say which one and where it looked, before blaming the script
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            ErrorType type = message.contains("not found") || message.contains("interpreter") ? ErrorType.USER
                    : ErrorType.ENVIRONMENT;
            return AtomResult.error(type, 40005, message);
        }

        String tail = result.output().strip();
        if (tail.length() > 8000) {
            tail = tail.substring(tail.length() - 8000);
        }
        AtomResult answer = AtomResult.success()
                .data("exitCode", String.valueOf(result.exitCode()))
                .data("durationMs", String.valueOf(result.durationMs()))
                // the names must not collide with the inputs: the runner merges the build environment --
                // including the previous step's exported outputs -- into the next step's input.json, so an
                // output called interpreterPath would pin the next script's interpreter to this one's path
                .data("usedInterpreter", interpreter.toolName())
                .data("usedInterpreterPath", String.valueOf(result.interpreter()))
                .data("outputTail", tail);

        if (result.timedOut()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40006, "script timed out after " + timeoutSeconds
                    + "s: " + interpreter.toolName() + " (" + result.durationMs() + " ms)");
        }
        if (result.exitCode() != 0 && failOnError) {
            return AtomResult.error(ErrorType.USER, 40007, "script exited with " + result.exitCode()
                    + (tail.isEmpty() ? "" : "; last output: " + lastLine(tail)));
        }
        context.log("[script] " + interpreter.toolName() + " exited " + result.exitCode()
                + " in " + result.durationMs() + " ms" + (failOnError ? "" : " (not failing the build)"));
        return answer;
    }

    /** {@code KEY=value} lines (or comma separated); blank lines and {@code #} comments are ignored. */
    static Map<String, String> parseEnv(String value) {
        Map<String, String> env = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return env;
        }
        for (String line : value.split("[,\\n]")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int split = trimmed.indexOf('=');
            if (split > 0) {
                env.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
            }
        }
        return env;
    }

    /** One argument per line: newline separated keeps quotes and spaces out of the parsing entirely. */
    static List<String> parseArgs(String value) {
        List<String> args = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return args;
        }
        for (String line : value.split("\\n")) {
            if (!line.isEmpty()) {
                args.add(line);
            }
        }
        return args;
    }

    private static String lastLine(String text) {
        int index = text.lastIndexOf('\n');
        String line = index >= 0 ? text.substring(index + 1) : text;
        return line.length() > 300 ? line.substring(line.length() - 300) : line;
    }
}
