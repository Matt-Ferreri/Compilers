## Building the compiler

ObjectWeb **ASM 9.7** is required on the classpath for `javac` (not needed at runtime for the generated `.class` files).

From the project root (Windows PowerShell example):

```powershell
javac -encoding UTF-8 -cp "lib\asm-9.7.jar" -d out_classes src\*.java
java -cp "out_classes;lib\asm-9.7.jar" main
```

On Unix-like systems, use `:` instead of `;` in the classpath.

After a successful compile of each source program, artifacts are written under **`out/`**:

- **`out/program_<N>.ll`** — LLVM IR text (verify with **`clang`** or **`lli`** when LLVM is installed).
- **`out/compile/Program<N>.class`** — JVM bytecode from ASM; run with `java -cp out compile.Program<N>`.
- **`out/java_src/compile/Program<N>.java`** — readable Java source generated from the AST; compile with **`javac`** to compare with the ASM path.

**`javac` / `java` on generated Java** (project root; ASM is **not** needed for this step):

```powershell
javac -encoding UTF-8 -d out\javac_classes out\java_src\compile\Program1.java
java -cp out\javac_classes compile.Program1
```

Classes land in **`out/javac_classes/compile/Program<N>.class`**, separate from **`out/compile/Program<N>.class`** (ASM), so you can compare bytecode (e.g. `javap -c`) or run both and compare stdout.

In `src/main.java`, toggle **`isLlvmVerbose`**, **`isJvmVerbose`**, **`isJavaSourceVerbose`**, **`runJvmAfterCompile`**, and **`runJavacAfterCompile`** to print artifacts or launch subprocesses.

## Getting Started

Welcome to the VS Code Java world. Here is a guideline to help you get started to write Java code in Visual Studio Code.

## Folder Structure

The workspace contains two folders by default, where:

- `src`: the folder to maintain sources
- `lib`: the folder to maintain dependencies

Meanwhile, the compiled output files will be generated in the `bin` folder by default.

> If you want to customize the folder structure, open `.vscode/settings.json` and update the related settings there.

## Dependency Management

The `JAVA PROJECTS` view allows you to manage your dependencies. More details can be found [here](https://github.com/microsoft/vscode-java-dependency#manage-dependencies).
