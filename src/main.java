import java.util.List;


public class main {
    public static void main(String[] args) throws Exception {
        // read the source code from a file
        String sourceCode = InputReader.ReadAll("C:/Users/ma8fe/compile/src/program.txt");

        // verbose mode that can be toggled on and off depending on how much output is
        // wanted
        final boolean isLexerVerbose = true;
        final boolean isParserVerbose = false;
        final boolean isSemanticAnalyzerVerbose = false;

        // create one lexer and let it keep track of where the next program starts
        Lex lex = new Lex();
        boolean compilationHadErrors = false;
        int lineNum = 1; // keep track of line numbers across programs for better error messages

        // compile one program at a time: lex -> parse -> semantic analysis
        for (int programNumber = 1; lex.hasMorePrograms(sourceCode); programNumber++) {
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
            Tree cst = parse.run(tokens, isParserVerbose);

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
            semanticAnalyzer.run(tokens, cst);

            if (semanticAnalyzer.semanticErrors()) {
                System.out.println("Semantic Analysis failed for program " + programNumber + ", moving to next...");
                compilationHadErrors = true;
                continue;
            }
            else {
                if (isSemanticAnalyzerVerbose) {
                    semanticAnalyzer.printAST();
                    semanticAnalyzer.printSymbolTable();
                }
            }

            System.out.println("No errors moving on to code generation...");
            System.out.println();
        }

        if (compilationHadErrors) {
            System.out.println("Compilation finished with errors.");
        } else {
            System.out.println("Compilation finished successfully.");
        }
    }

}