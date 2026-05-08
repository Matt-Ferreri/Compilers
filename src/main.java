import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class main {
    public static void main(String[] args) throws Exception {
        // read the source code from a file
        String sourceCode = InputReader.ReadAll("C:/Users/ma8fe/compile/Programs/program.txt");

        // verbose mode that can be toggled on and off depending on how much output is
        // wanted
        final boolean isLexerVerbose = false;
        final boolean isParserVerbose = false;
        final boolean isSemanticAnalyzerVerbose = false;
        final boolean isCodeGeneratorVerbose = false;
        final boolean isLlvmVerbose = false;
        final boolean isJvmVerbose = false;
        final boolean isJavaSourceVerbose = false;
        /** If true, print emitted TypeScript and paths (same idea as Java source verbose). */
        final boolean isTypeScriptVerbose = false;
        /** If true, run {@code java -cp out compile.ProgramN} after successful JVM codegen. */
        final boolean runJvmAfterCompile = false;
        /** If true, run {@code javac -d out/javac_classes} then {@code java -cp out/javac_classes compile.ProgramN} on emitted source. */
        final boolean runJavacAfterCompile = false;
        /**
         * If true, run {@code tsc} on {@code out/ts_src/compile/ProgramN.ts} into {@code out/ts_js}, then
         * {@code node} on the emitted JS. Requires TypeScript on PATH ({@code npm i -g typescript}) and Node.js.
         */
        final boolean runTscAfterCompile = true;

        // create one lexer and let it keep track of where the next program starts
        Lex lex = new Lex();
        boolean compilationHadErrors = false;
        int lineNum = 1; // keep track of line numbers across programs for better error messages

        // compile one program at a time: lex -> parse -> semantic analysis - > code
        // generation
        for (int programNumber = 1; lex.hasMorePrograms(sourceCode); programNumber++) {
            // all programs start with no errors
            compilationHadErrors = false;
            System.out.println("Compiling program " + programNumber + "...");

            System.out.println("Starting lexing...");
            // lex only the next program up to its EOP and keep the rest for later
            List<Token> tokens = lex.runNextProgram(sourceCode, isLexerVerbose, lineNum);
            lineNum = lex.getCurrentLine(); // update line number for next program
            lex.getWarnings();

            if (lex.lexErrors()) {
                System.out.println("Lexing failed for program " + programNumber + ", moving to next...");
                compilationHadErrors = true;
                continue;
            } else {
                System.out.println("No errors moving on to parse...");
                System.out.println();
            }

            Parser parse = new Parser();
            System.out.println("Starting Parse...");
            // parse just the current program's token stream
            Tree cst = parse.run(tokens, isParserVerbose, compilationHadErrors);

            if (parse.parseErrors()) {
                System.out.println("Parsing failed for program " + programNumber + ", moving to next...");
                compilationHadErrors = true;
                continue;
            }

            System.out.println("No errors moving on to semantic analysis...");
            System.out.println();

            SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
            System.out.println("Starting Semantic Analysis...");
            // semantic analysis reduces the CST into an AST and checks scopes/types
            Tree ast = semanticAnalyzer.run(tokens, cst);

            // if there are semantic errors, move on to the next program, otherwise print
            // the AST and symbol table if in verbose mode
            if (semanticAnalyzer.semanticErrors()) {
                System.out.println("Semantic Analysis failed for program " + programNumber + ", moving to next...");
                compilationHadErrors = true;
                continue;
            }
            else if (isSemanticAnalyzerVerbose) {
                semanticAnalyzer.printAST();
                semanticAnalyzer.printAndReturnSymbolTable();
            }

            System.out.println("No errors moving on to code generation...");
            System.out.println();

            // code generation takes the AST and symbol table and generates code
            CodeGen codeGenerator = new CodeGen();
            codeGenerator.run(ast, semanticAnalyzer);

            // if verbose mode is on print the code generation in hex
            if (isCodeGeneratorVerbose) {
                codeGenerator.printCodeGrid();
            }
            // code gen shouldn't have errors, but just in case to fail gracefully
            // only error is if the stack/heap overlap
            if (codeGenerator.hasErrors()) {
                compilationHadErrors = true;
            }

            // LLVM IR and JVM bytecode — only after semantic success (we are past that gate)
            if (!compilationHadErrors) {
                LlvmCodeGen llvm = new LlvmCodeGen();
                llvm.run(ast, semanticAnalyzer);
                if (llvm.hasErrors()) {
                    compilationHadErrors = true;
                    for (String e : llvm.getErrors()) {
                        System.err.println("LLVM codegen: " + e);
                    }
                } else {
                    try {
                        llvm.writeIrFile(programNumber);
                        if (isLlvmVerbose) {
                            Path ll = Path.of("out", "program_" + programNumber + ".ll");
                            System.out.println("LLVM IR written: " + ll.toAbsolutePath());
                            System.out.println(llvm.getLlvmIr());
                        }
                    } catch (Exception ex) {
                        compilationHadErrors = true;
                        System.err.println("LLVM write failed: " + ex.getMessage());
                    }
                }

                JvmAsmCodeGen jvmCg = new JvmAsmCodeGen();
                jvmCg.run(ast, semanticAnalyzer, programNumber);
                if (jvmCg.hasErrors()) {
                    compilationHadErrors = true;
                    for (String e : jvmCg.getErrors()) {
                        System.err.println("JVM codegen: " + e);
                    }
                } else {
                    try {
                        jvmCg.writeClassFile();
                        if (isJvmVerbose) {
                            byte[] bc = jvmCg.getBytecode();
                            System.out.println("JVM class written: out/compile/Program" + programNumber + ".class ("
                                    + (bc == null ? 0 : bc.length) + " bytes)");
                        }
                        if (runJvmAfterCompile) {
                            runJavaSubprocess(programNumber);
                        }
                    } catch (Exception ex) {
                        compilationHadErrors = true;
                        System.err.println("JVM write/run failed: " + ex.getMessage());
                    }
                }

                JavaSourceCodeGen javaSrc = new JavaSourceCodeGen();
                javaSrc.run(ast, semanticAnalyzer, programNumber);
                if (javaSrc.hasErrors()) {
                    compilationHadErrors = true;
                    for (String e : javaSrc.getErrors()) {
                        System.err.println("Java source codegen: " + e);
                    }
                } else {
                    try {
                        javaSrc.writeJavaFile();
                        if (isJavaSourceVerbose) {
                            Path jp = Path.of("out", "java_src", "compile", "Program" + programNumber + ".java");
                            System.out.println("Java source written: " + jp.toAbsolutePath());
                            System.out.println(javaSrc.getJavaSource());
                        }
                        if (runJavacAfterCompile) {
                            runJavacAndJavaSubprocess(programNumber);
                        }
                    } catch (Exception ex) {
                        compilationHadErrors = true;
                        System.err.println("Java source write/javac failed: " + ex.getMessage());
                    }
                }

                TypeScriptSourceCodeGen tsSrc = new TypeScriptSourceCodeGen();
                tsSrc.run(ast, semanticAnalyzer, programNumber);
                if (tsSrc.hasErrors()) {
                    compilationHadErrors = true;
                    for (String e : tsSrc.getErrors()) {
                        System.err.println("TypeScript source codegen: " + e);
                    }
                } else {
                    try {
                        tsSrc.writeTypeScriptFile();
                        if (isTypeScriptVerbose) {
                            Path tp = Path.of("out", "ts_src", "compile", "Program" + programNumber + ".ts");
                            System.out.println("TypeScript source written: " + tp.toAbsolutePath());
                            System.out.println(tsSrc.getTypeScriptSource());
                        }
                        if (runTscAfterCompile) {
                            runTscAndNodeSubprocess(programNumber);
                        }
                    } catch (Exception ex) {
                        compilationHadErrors = true;
                        System.err.println("TypeScript write/tsc failed: " + ex.getMessage());
                    }
                }
            }

        }

        if (compilationHadErrors) {
            System.out.println("Compilation finished with errors.");
        } else {
            System.out.println("Compilation finished successfully.");
        }
    }

    private static void runJavaSubprocess(int programNumber) throws Exception {
        String cp = "out";
        String mainClass = "compile.Program" + programNumber;
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", cp, mainClass);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            System.err.println("java subprocess timed out for " + mainClass);
            return;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!out.isEmpty()) {
            System.out.println("[java " + mainClass + "]\n" + out);
        }
        if (p.exitValue() != 0) {
            System.err.println("java exited with " + p.exitValue() + " for " + mainClass);
        }
    }

    private static void runJavacAndJavaSubprocess(int programNumber) throws Exception {
        Files.createDirectories(Path.of("out", "javac_classes"));
        Path javaFile = Path.of("out", "java_src", "compile", "Program" + programNumber + ".java");
        ProcessBuilder javacPb = new ProcessBuilder(
                "javac", "-encoding", "UTF-8", "-d", "out/javac_classes",
                javaFile.toString().replace('\\', '/'));
        javacPb.redirectErrorStream(true);
        Process jc = javacPb.start();
        boolean jcDone = jc.waitFor(60, TimeUnit.SECONDS);
        if (!jcDone) {
            jc.destroyForcibly();
            System.err.println("javac timed out for Program" + programNumber);
            return;
        }
        String jcOut = new String(jc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!jcOut.isEmpty()) {
            System.out.println("[javac Program" + programNumber + "]\n" + jcOut);
        }
        if (jc.exitValue() != 0) {
            System.err.println("javac exited with " + jc.exitValue() + " for Program" + programNumber);
            return;
        }

        ProcessBuilder javaPb = new ProcessBuilder(
                "java", "-cp", "out/javac_classes", "compile.Program" + programNumber);
        javaPb.redirectErrorStream(true);
        Process jv = javaPb.start();
        boolean jvDone = jv.waitFor(30, TimeUnit.SECONDS);
        if (!jvDone) {
            jv.destroyForcibly();
            System.err.println("java (javac output) timed out for compile.Program" + programNumber);
            return;
        }
        String jvOut = new String(jv.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!jvOut.isEmpty()) {
            System.out.println("[java compile.Program" + programNumber + " from javac]\n" + jvOut);
        }
        if (jv.exitValue() != 0) {
            System.err.println("java exited with " + jv.exitValue() + " for javac-built Program" + programNumber);
        }
    }

    /**
     * Windows installs {@code tsc} as {@code tsc.cmd}; {@link ProcessBuilder} does not run {@code .cmd}
     * shims when the program name is {@code tsc}. Using {@code cmd.exe /c tsc ...} lets the shell resolve
     * it the same way as an interactive terminal.
     */
    private static boolean isWindowsOs() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static void runTscAndNodeSubprocess(int programNumber) throws Exception {
        Files.createDirectories(Path.of("out", "ts_js", "compile"));
        Path tsFile = Path.of("out", "ts_src", "compile", "Program" + programNumber + ".ts");
        String tsArg = tsFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        ProcessBuilder tscPb;
        if (isWindowsOs()) {
            tscPb = new ProcessBuilder("cmd.exe", "/c", "tsc",
                    "--outDir", "out/ts_js",
                    "--rootDir", "out/ts_src",
                    "--module", "commonjs",
                    "--target", "ES2020",
                    "--strict",
                    tsArg);
        } else {
            tscPb = new ProcessBuilder("tsc",
                    "--outDir", "out/ts_js",
                    "--rootDir", "out/ts_src",
                    "--module", "commonjs",
                    "--target", "ES2020",
                    "--strict",
                    tsArg);
        }
        tscPb.redirectErrorStream(true);
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        tscPb.directory(projectRoot.toFile());
        Process tscProc = tscPb.start();
        boolean tscDone = tscProc.waitFor(120, TimeUnit.SECONDS);
        if (!tscDone) {
            tscProc.destroyForcibly();
            System.err.println("tsc timed out for Program" + programNumber);
            return;
        }
        String tscOut = new String(tscProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!tscOut.isEmpty()) {
            System.out.println("[tsc Program" + programNumber + "]\n" + tscOut);
        }
        if (tscProc.exitValue() != 0) {
            System.err.println("tsc exited with " + tscProc.exitValue() + " for Program" + programNumber
                    + " (is TypeScript installed? Try: npm install -g typescript)");
            return;
        }

        Path jsFile = Path.of("out", "ts_js", "compile", "Program" + programNumber + ".js");
        ProcessBuilder nodePb = new ProcessBuilder(
                "node", jsFile.toAbsolutePath().normalize().toString());
        nodePb.directory(projectRoot.toFile());
        nodePb.redirectErrorStream(true);
        Process nodeProc = nodePb.start();
        boolean nodeDone = nodeProc.waitFor(60, TimeUnit.SECONDS);
        if (!nodeDone) {
            nodeProc.destroyForcibly();
            System.err.println("node timed out for Program" + programNumber);
            return;
        }
        String nodeOut = new String(nodeProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!nodeOut.isEmpty()) {
            System.out.println("[node out/ts_js/compile/Program" + programNumber + ".js]\n" + nodeOut);
        }
        if (nodeProc.exitValue() != 0) {
            System.err.println("node exited with " + nodeProc.exitValue() + " for Program" + programNumber);
        } 
    }
}
