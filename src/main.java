import java.util.List;

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
            else if (isSemanticAnalyzerVerbose && !compilationHadErrors) {
                semanticAnalyzer.printAST();
                semanticAnalyzer.printAndReturnSymbolTable();
            }

            System.out.println("No errors moving on to code generation...");
            System.out.println();

            // code generation takes the AST and symbol table and generates code
            CodeGen codeGenerator = new CodeGen();
            codeGenerator.run(ast, semanticAnalyzer);

            // if verbose mode is on print the code generation in hex
            if (isCodeGeneratorVerbose && !compilationHadErrors) {
                codeGenerator.printCodeGrid();
            }
            // code gen shouldn't have errors, but just in case to fail gracefully
            // only error is if the stack/heap overlap
            if (codeGenerator.hasErrors()) {
                compilationHadErrors = true;
            }

        }

        if (compilationHadErrors) {
            System.out.println("Compilation finished with errors.");
        } else {
            System.out.println("Compilation finished successfully.");
        }
    }

}
