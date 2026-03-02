import java.util.List;

public class Parser {

    // start with no errors or warnings
    boolean hasErrors = false;
    boolean hasWarnings = false;
    int errors = 0;
    int warnings = 0;

    private boolean verbose;

    // takes in the message, if verbose is on, prints the message
    private void log(String message) {
        if (verbose) {
            System.out.println(message);
        }
    }

    // takes message in as a variable and and always prints message
    private void logError(String message) {
        System.out.println(message);
    }

    // takes message in as a variable and always warning prints message
    private void logWarning(String message) {
        System.out.println(message);
    }

    // if there is an error, set hasErrors to true
    public boolean parseErrors() {
        // check for lexing errors
        if (hasErrors) {
            logError("Stopping program ... " + errors + " error(s) found.");
        }
        return hasErrors;
    }

    // runs the parser
    public void run(List<Token> tokens, boolean isVerbose) {
        // set this verbose equal to the verbose it takes in
        this.verbose = isVerbose;
        int current = 0; // use to keep track of where we will be in the token stream

        // perform recursive descent, start at the outmost part "Program"
        parseProgram(tokens, current);
    }

    // match takes in a token type and ensures the character matches the expected
    // token type
    private void match(Lex.characterType expected, List<Token> currentTokens, int currentPosition) {
        if (currentTokens.get(currentPosition).tokenType == expected) {
            return;
        }
        logError("PARSER: Expected " + expected + " but got " + currentTokens.get(currentPosition).tokenType
                + " at line " + currentTokens.get(currentPosition).line + " and column "
                + currentTokens.get(currentPosition).position);
        hasErrors = true;
        errors++;
    }

    private void parseProgram(List<Token> tokens, int current) {
        // a program is a Block + $
        parseBlock(tokens, current);
        // the program must end with $
        match(Lex.characterType.EOP, tokens, current);
    }
}
