package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.File;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The script atom, started the way the runner starts it.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: the exit code and what it means, env/args, quoting and unicode
 * surviving the trip to the interpreter, scriptFile, workdir, the timeout, the refusals, and envFromStdout.
 */
class ScriptIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a plain script runs and reports its exit code and output")
    void runsAPlainScript() throws Exception {
        AtomResult run = atom.input("{\"script\": \"echo hello-from-atom\"}").run();

        run.assertOk();
        assertEquals("0", run.field("exitCode"), run.file("output.json"));
        assertTrue(run.file("output.json").contains("hello-from-atom"), run.file("output.json"));
    }

    @Test
    @DisplayName("a non-zero exit fails the step and names the code")
    void failsOnANonZeroExit() throws Exception {
        AtomResult run = atom.input("{\"script\": \"echo about-to-fail; exit 3\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("\"errorCode\":40007"), run.file("output.json"));
        assertTrue(run.file("output.json").contains("exited with 3"), run.file("output.json"));
    }

    @Test
    @DisplayName("failOnError=false reports the code instead of failing")
    void reportsTheCodeWhenAsked() throws Exception {
        AtomResult run = atom.input("{\"script\": \"exit 3\", \"failOnError\": \"false\"}").run();

        run.assertOk();
        assertEquals("3", run.field("exitCode"), run.file("output.json"));
    }

    @Test
    @DisplayName("environment and arguments reach the script")
    void passesEnvAndArgs() throws Exception {
        AtomResult run = atom.input("{\"script\": \"echo \\\"env=$SCOPED arg1=$1 arg2=$2\\\"\","
                        + " \"env\": \"SCOPED=42\", \"args\": \"alpha\\nbeta\"}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("env=42 arg1=alpha arg2=beta"), run.file("output.json"));
    }

    @Test
    @DisplayName("quotes and non-ASCII survive, so the script did not go through a shell that rewrote it")
    void keepsQuotingAndUnicode() throws Exception {
        AtomResult run = atom.input("{\"script\": \"echo 'single $notexpanded'; echo \\\"中文 🚀\\\"\"}").run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("single $notexpanded"),
                "quoting was rewritten:\n" + run.file("output.json"));
        assertTrue(run.file("output.json").contains("中文"), "unicode was mangled:\n" + run.file("output.json"));
    }

    @Test
    @DisplayName("scriptFile runs the file from the workspace")
    void runsAScriptFile() throws Exception {
        AtomResult run = atom.file("task.sh", "echo from-the-file\n")
                .input("{\"scriptFile\": \"task.sh\"}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("from-the-file"), run.file("output.json"));
    }

    @Test
    @DisplayName("workdir is honoured")
    void runsInTheWorkdir() throws Exception {
        AtomResult run = atom.file("sub/.keep", "")
                .input("{\"script\": \"echo marker > here.txt\", \"workdir\": \"sub\"}")
                .run();

        run.assertOk();
        assertEquals("marker\n", run.file("sub/here.txt"), "the script did not run in the workdir");
    }

    @Test
    @DisplayName("a hung script is reported as a timeout, not as exit 143")
    void reportsTheTimeout() throws Exception {
        AtomResult run = atom.input("{\"script\": \"sleep 5\", \"timeoutSeconds\": \"1\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("\"errorCode\":40006"), run.file("output.json"));
        assertTrue(run.file("output.json").contains("timed out"), run.file("output.json"));
    }

    @Test
    @DisplayName("bad input is named: interpreter, interpreter path, and a missing script file")
    void refusesBadInput() throws Exception {
        AtomResult interpreter = atom.input("{\"interpreter\": \"brainfuck\", \"script\": \"+++\"}").run();
        interpreter.assertRefused();
        assertTrue(interpreter.file("output.json").contains("\"errorCode\":40001"), interpreter.file("output.json"));

        AtomResult path = atom.input("{\"interpreter\": \"python\", \"interpreterPath\": \"/nonexistent/python3\","
                + " \"script\": \"print(1)\"}").run();
        path.assertRefused();
        assertTrue(path.file("output.json").contains("not found"), path.file("output.json"));

        AtomResult missing = atom.input("{\"scriptFile\": \"nope.sh\"}").run();
        missing.assertRefused();
        assertTrue(missing.file("output.json").contains("\"errorCode\":40003"), missing.file("output.json"));
    }

    @Test
    @DisplayName("a python script runs when python3 is on this machine")
    void runsPython() throws Exception {
        assumeTrue(onPath("python3"), "python3 is not on PATH on this machine");
        AtomResult run = atom.input("{\"interpreter\": \"python\", \"script\": \"import os\\nprint(\\\"py\\\","
                        + " os.environ.get(\\\"WHO\\\"))\\n\", \"env\": \"WHO=atom\"}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("py atom"), run.file("output.json"));
    }

    @Test
    @DisplayName("a node script runs when node is on this machine")
    void runsNode() throws Exception {
        assumeTrue(onPath("node"), "node is not on PATH on this machine");
        AtomResult run = atom.input("{\"interpreter\": \"node\", \"script\": \"console.log(\\\"node ok\\\")\"}").run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("node ok"), run.file("output.json"));
    }

    @Test
    @DisplayName("bat and powershell are Windows-only")
    void runsWindowsInterpreters() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("win"), "the bat/powershell interpreters only exist on a Windows host (os.name=" + os + ")");

        atom.input("{\"interpreter\": \"bat\", \"script\": \"echo bat-works\"}").run().assertOk();
        AtomResult powershell = atom.input("{\"interpreter\": \"powershell\", \"script\": \"Write-Output pwsh-works\"}")
                .run();
        powershell.assertOk();
        assertTrue(powershell.file("output.json").contains("pwsh-works"), powershell.file("output.json"));
    }

    @Test
    @DisplayName("envFromStdout turns the printed KEY=VALUE lines into variables, never shadowing an input")
    void exportsVariablesFromStdout() throws Exception {
        AtomResult run = atom.input("{\"script\": \"printf \\\"FOO=bar\\\\nDB: postgres\\\\nPATH+/opt/extra/bin\\\\n"
                        + "# comment\\\\nscript=shadow\\\\n\\\"\", \"envFromStdout\": \"true\"}")
                .run();

        run.assertOk();
        String output = run.file("output.json");
        assertTrue(output.contains("\"FOO\":{\"type\":\"string\",\"value\":\"bar\"}"), "FOO not exported:\n" + output);
        assertTrue(output.contains("\"DB\":{\"type\":\"string\",\"value\":\"postgres\"}"),
                "KEY: VALUE form not accepted:\n" + output);
        assertTrue(run.field("PATH").endsWith("/opt/extra/bin"), "PATH append did not work: " + run.field("PATH"));
        assertFalse(output.contains("\"script\":{\"type\":\"string\",\"value\":\"shadow\"}"),
                "an input name was shadowed by script output:\n" + output);
    }

    @Test
    @DisplayName("envFromStdout is off by default, and output that is not a variable is not an error")
    void keepsEnvFromStdoutOffByDefault() throws Exception {
        AtomResult quiet = atom.input("{\"script\": \"printf \\\"QUIET=1\\\\n\\\"\"}").run();
        quiet.assertOk();
        assertFalse(quiet.file("output.json").contains("\"QUIET\""),
                "envFromStdout must be off by default:\n" + quiet.file("output.json"));

        AtomResult noise = atom.input("{\"script\": \"printf \\\"# only comments\\\\nnot a variable line\\\\n\\\"\","
                + " \"envFromStdout\": \"true\"}").run();
        noise.assertOk();
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, tool).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
