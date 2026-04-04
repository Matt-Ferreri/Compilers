import java.util.List;


public class main {
    public static void main(String[] args) throws Exception {
        // read the source code from a file
        String sourceCode = InputReader.ReadAll("C:/Users/ma8fe/compile/src/program.txt");

        // verbose mode that can be toggled on and off depending on how much output is
        // wanted
        final boolean isLexerVerbose = false;
        final boolean isParserVerbose = false;
        final boolean isSemanticAnalyzerVerbose = false;

        // perform lexical analysis on the source code
        Lex lex = new Lex();

        System.out.println("Starting lexing...");

        // go to lex.run with the source code and the condition of verbose mode
        // lex.run returns the list of tokens, so assign it to the tokens variable
        List<Token> tokens = lex.run(sourceCode, isLexerVerbose);

        // print how many warnings there were and what they are
        lex.getWarnings();

        // if there were any lexing errors, print a message and stop the program,
        // otherwise print a success message
        if (lex.lexErrors()) {
            System.out.println("Lexing failed stopping...");
            return;
        } else {
            System.out.println("No errors moving on to parse...");
        }
        // move on to parse

        // create new parser instance
        Parser parse = new Parser();
        System.out.println("Starting Parse...");

        // perform parse using the tokens list and the boolean verbose to control output
        // set it equal to tree so that the CST can be printed out
        List<Tree> tree = parse.run(tokens, isParserVerbose);

        // if there were any parse errors, print a message and stop the program,
        // otherwise print a success message
        if (parse.parseErrors()) {
            System.out.println("Parsing failed stopping...");
            return;
        }
        // if we make it here, then there were no parse errors
        // System.out.print(tree);
        System.out.println("No errors moving on to semantic analysis...");

        // move on to semantic analysis

        // create new semantic analyzer instance
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
        System.out.println("Starting Semantic Analysis...");
        // semantic analysis is a tree of hash tables

        // perform semantic analysis using the tree of hash tables
        semanticAnalyzer.run(tokens, isSemanticAnalyzerVerbose);
        // if there were any semantic errors, print a message and stop the program,
        // otherwise print a success message
        if (semanticAnalyzer.semanticErrors()) {
            System.out.println("Semantic Analysis failed stopping...");
            return;
        }
        // if we make it here, then there were no semantic errors, so we perform code gen
        System.out.println("No errors moving on to code generation...");
    }

}