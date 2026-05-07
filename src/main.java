import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class main {
    public static void main(String[] args) throws Exception {
        // read the source code from a file
        String sourceCode = InputReader.ReadAll("C:/Users/ma8fe/compile/Programs/program.txt");

        // verbose mode that can be toggled on and off depending on how much output is
        // wanted
        final boolean isLexerVerbose = true;
        final boolean isParserVerbose = true;
        final boolean isSemanticAnalyzerVerbose = true;
        final boolean isCodeGeneratorVerbose = true;
        final boolean isLlvmVerbose = true;
        final boolean isJvmVerbose = true;
        /** If true, run {@code java -cp out compile.ProgramN} after successful JVM codegen. */
        final boolean runJvmAfterCompile = true;

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
}
