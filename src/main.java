import java.util.List;

public class main {
    public static void main(String[] args) throws Exception {
        // read the source code from a file
        String sourceCode = InputReader.ReadAll("C:/Users/ma8fe/compile/src/program.txt");
        
        // verbose mode that can be toggled on and off depending on how much output is wanted
        final boolean isLexerVerbose = true;
        final boolean isParserVerbose = true;

        // perform lexical analysis on the source code
        Lex lex = new Lex();

        System.out.println("Starting lexing...");

        //go to lex.run with the source code and the condition of verbose mode
        // lex.run returns the list of tokens, so assign it to the tokens variable
        List<Token> tokens = lex.run(sourceCode, isLexerVerbose);


        
        // print how many warnings there were and what they are
        lex.getWarnings();

        // if there were any lexing errors, print a message and stop the program, otherwise print a success message
        if (lex.lexErrors()) {
            System.out.println("Lexing failed stopping...");
            return;
        }
        else{
            System.out.println("No errors moving on to parse...");
        }

    
        // move on to parse

        //create new parser instance
        Parser parse = new Parser(); 
        System.out.println("Starting Parse...");

        // perform parse using the tokens list and the boolean verbose to control output
        parse.run(tokens, isParserVerbose);

                // if there were any parse errors, print a message and stop the program, otherwise print a success message
        if (parse.parseErrors()) {
            System.out.println("Parsing failed stopping...");
            return;
        }
        else{
            System.out.println("No errors moving on to semantic analysis...");
        }
    
    }

}