"""Exercise production shell sessions on the host JVM without Android or Maven."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ShellTerminalTest(unittest.TestCase):
    def test_stop_and_durable_exit_receipts(self):
        # Only JSON's in-memory value access is needed; no Android stub methods run.
        stubs = {
            "JSONException": "public class JSONException extends RuntimeException {}",
            "JSONArray": "public class JSONArray { public JSONArray put(Object value){return this;} }",
            "JSONObject": """public class JSONObject {
                public static final Object NULL=new Object();
                private final java.util.Map<String,Object> values=new java.util.HashMap<>();
                public JSONObject put(String key,Object value){values.put(key,value);return this;}
                public String getString(String key){return (String)values.get(key);}
                public int getInt(String key){return ((Number)values.get(key)).intValue();}
                public String optString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
                public String toString(){return values.toString();}
            }""",
        }
        with tempfile.TemporaryDirectory(prefix="pocket-shell-test-") as directory:
            temporary = pathlib.Path(directory)
            package = temporary / "org/json"
            package.mkdir(parents=True)
            sources = []
            for name, body in stubs.items():
                source = package / f"{name}.java"
                source.write_text("package org.json;\n" + body)
                sources.append(str(source))
            sources.extend(str(ROOT / path) for path in [
                "app/src/main/java/dev/pocket/agent/Json.java",
                "app/src/main/java/dev/pocket/agent/ShellTerminal.java",
                "scripts/ShellTerminalSessionTest.java",
            ])
            classes = temporary / "classes"
            subprocess.run(["javac", "--release", "17", "-d", str(classes), *sources], check=True)
            subprocess.run([
                "java", "-cp", str(classes), "dev.pocket.agent.ShellTerminalSessionTest",
                str(temporary / "workspace"), "/bin/sh",
            ], check=True, timeout=15)


if __name__ == "__main__":
    unittest.main()
