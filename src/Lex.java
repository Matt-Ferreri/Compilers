import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Lex {

    // different states that the lexer can be in
    // BEGIN: the initial state of the lexer, where it is waiting to encounter a
    // character
    // Identifier, number, string, operator, separator, keyword: the states that the
    // lexer can be in when it is building a token of that type
    // will go into comment state when it encounters /* and will stay in that state
    // until it encounters */, at which point it will return to the BEGIN state
    // EOP: the state that the lexer will be in when it encounters the end of the
    // program, which is denoted by the $ character
    // ERROR: the state that the lexer will be in when it encounters an unrecognized
    // character
    // DONE: the state that the lexer will be in when it has finished lexing a block
    // of code and is ready to return to the BEGIN state to start building the next
    // token
    enum state {
        BEGIN, IDENTIFIER, NUMBER, STRING, STRING_ERROR, COMMENT, EQUAL, NOT_EQUAL,
        BEGIN_COMMENT_CHECK, END_COMMENT_CHECK, EQUAL_CHECK, NOT_EQUAL_CHECK, EOP, ERROR, DONE
    }

    // different types of characters that the lexer can encounter
    // PRINT, LOOP, IF, TYPE, BOOLVAL are token types for keywords (not input
    // character types)
    enum characterType {
        ID, DIGIT, LBRACE, RBRACE, LPAREN, RPAREN, STRING, PLUS, SLASH, STAR,
        EQUAL, BOOLOP, EXCLAMATION, WHITESPACE, NEWLINE, EOP, OTHER,
        PRINT, LOOP, IF, TYPE, BOOLVAL
    }

    // start with no errors
    boolean hasErrors = false;

    // start with no errors or warnings
    int warnings = 0;
    int errors = 0;

    // build state transition table, where the rows are the current state and the
    // columns are the character type, and the values are the next state
    // it is of the size of the number of states by the number of character types
    state[][] transitionTable = new state[state.values().length][characterType.values().length];

    // list of keywords mapped to their corresponding token type
    // will be used by Parser so it can identifty what type it is
    private static final String[][] KEYWORDS = {
            { "print", "print" }, { "while", "loop" }, { "if", "if" }, { "int", "Type" }, { "string", "Type" },
            { "boolean", "Type" }, { "false", "BoolVal" }, { "true", "BoolVal" }
    };

    // regex pattern to match any keyword (exact whole-string match)
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("^(print|while|if|int|string|boolean|false|true)$");

    private boolean verbose;

    // takes message in as a variable and prints message if verbose mode is on
    private void log(String message) {
        if (verbose) {
            System.out.println(message);
        }
    }

    // takes message in as a variable and and always prints message
    private void logError(String message) {
        System.out.println(message);
    }

    // list of tokens to be returned for parser
    List<Token> tokens = new ArrayList<>();

    // run returns the list of tokens, and takes in the source code and the
    // condition of verbose mode
    public List<Token> run(String sourceCode, boolean isVerbose) {
        this.verbose = isVerbose;
        /*
         * representation of state:
         * BEGIN, IDENTIFIER, NUMBER, STRING, COMMENT, EQUAL, NOT_EQUAL,
         * BEGIN_COMMENT_CHECK, END_COMMENT_CHECK, EQUAL_CHECK, NOT_EQUAL_CHECK, EOP,
         * ERROR, DONE
         * Char: 0, Digit: 1, LBRACE: 2, RBRACE: 3, LPAREN: 4, RPAREN: 5, STRING: 6,
         * PLUS: 7, EQUAL: 8, WHITESPACE: 9
         */

        // fill in the transition table for the BEGIN state
        // go to appropriate state based on character
        transitionTable[state.BEGIN.ordinal()][characterType.ID.ordinal()] = state.IDENTIFIER;
        transitionTable[state.BEGIN.ordinal()][characterType.DIGIT.ordinal()] = state.NUMBER;
        transitionTable[state.BEGIN.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.BEGIN.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.BEGIN.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.BEGIN.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.BEGIN.ordinal()][characterType.STRING.ordinal()] = state.STRING;
        transitionTable[state.BEGIN.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.BEGIN.ordinal()][characterType.EQUAL.ordinal()] = state.EQUAL_CHECK;
        transitionTable[state.BEGIN.ordinal()][characterType.EXCLAMATION.ordinal()] = state.NOT_EQUAL_CHECK;
        transitionTable[state.BEGIN.ordinal()][characterType.SLASH.ordinal()] = state.BEGIN_COMMENT_CHECK;
        transitionTable[state.BEGIN.ordinal()][characterType.STAR.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN.ordinal()][characterType.NEWLINE.ordinal()] = state.BEGIN;
        transitionTable[state.BEGIN.ordinal()][characterType.WHITESPACE.ordinal()] = state.BEGIN;
        transitionTable[state.BEGIN.ordinal()][characterType.EOP.ordinal()] = state.EOP;
        transitionTable[state.BEGIN.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill in the transition table for the IDENTIFIER state
        // everything goes to done other than IDENTIFIER and ERROR
        transitionTable[state.IDENTIFIER.ordinal()][characterType.ID.ordinal()] = state.IDENTIFIER;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.DIGIT.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.EQUAL.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.EXCLAMATION.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.STAR.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.NEWLINE.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.EOP.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.WHITESPACE.ordinal()] = state.DONE;
        transitionTable[state.IDENTIFIER.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill the transition table for the NUMBER state
        // other than other characters everything goes to done since numbers can't be
        // more then single digits
        transitionTable[state.NUMBER.ordinal()][characterType.ID.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.DIGIT.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.EQUAL.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.EXCLAMATION.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.STAR.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.NEWLINE.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.EOP.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.WHITESPACE.ordinal()] = state.DONE;
        transitionTable[state.NUMBER.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill the transition table for the STRING state
        // only spaces and lowercase characters are valid within a string
        // everything else goes to the STRING_ERROR state
        transitionTable[state.STRING.ordinal()][characterType.ID.ordinal()] = state.STRING;
        transitionTable[state.STRING.ordinal()][characterType.DIGIT.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.LBRACE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.RBRACE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.LPAREN.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.RPAREN.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.STRING.ordinal()][characterType.PLUS.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.EQUAL.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.EXCLAMATION.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.SLASH.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.STAR.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.NEWLINE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.EOP.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING.ordinal()][characterType.WHITESPACE.ordinal()] = state.STRING;
        transitionTable[state.STRING.ordinal()][characterType.OTHER.ordinal()] = state.STRING_ERROR;

        // fill the transition table for the STRING_ERROR state
        // here it will go to the ERROR state since the entire string is not valid
        // this is necessary as going to ERROR state from string will reset the lexer to
        // the beginning and the closing " will be seen as an opening "
        // this will then cause an error when the next charcater that is not a lowercase
        // letter or white space appears even if it is valid
        // goes back to beginning with the closing " is found so it can start building
        // the next token
        transitionTable[state.STRING_ERROR.ordinal()][characterType.ID.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.DIGIT.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.LBRACE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.RBRACE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.LPAREN.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.RPAREN.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.STRING.ordinal()] = state.BEGIN;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.PLUS.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.EQUAL.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.EXCLAMATION.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.SLASH.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.STAR.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.NEWLINE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.EOP.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.WHITESPACE.ordinal()] = state.STRING_ERROR;
        transitionTable[state.STRING_ERROR.ordinal()][characterType.OTHER.ordinal()] = state.STRING_ERROR;

        // fill the transition table for the COMMENT state
        // if it encounters a *, it goes to the END_COMMENT_CHECK state, where it checks
        // if the next character is a /, and if so, it goes back to the BEGIN state
        // If it encounters any other character, it stays in the COMMENT state
        transitionTable[state.COMMENT.ordinal()][characterType.ID.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.DIGIT.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.LBRACE.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.RBRACE.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.LPAREN.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.RPAREN.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.STRING.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.PLUS.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.EQUAL.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.EXCLAMATION.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.SLASH.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.STAR.ordinal()] = state.END_COMMENT_CHECK;
        transitionTable[state.COMMENT.ordinal()][characterType.NEWLINE.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.EOP.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.WHITESPACE.ordinal()] = state.COMMENT;
        transitionTable[state.COMMENT.ordinal()][characterType.OTHER.ordinal()] = state.COMMENT;

        // fill in the transition table for the EQUAL state
        // if it encounters a =, it goes to the EQUAL_CHECK state, where it checks if
        // the next character is a =, and if so, it goes to the equal check state to
        // look for ==
        // And if not, it goes to the DONE state
        transitionTable[state.EQUAL.ordinal()][characterType.ID.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.DIGIT.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.EQUAL.ordinal()] = state.EQUAL_CHECK;
        transitionTable[state.EQUAL.ordinal()][characterType.EXCLAMATION.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.STAR.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.NEWLINE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.EOP.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.WHITESPACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill in the transition table for the NOT_EQUAL state
        // if it encounters a !, it goes to the NOT_EQUAL_CHECK state, where it checks
        // if the next character is a =, and if so, it goes to the not equal check state
        // to look for !=
        // And if not, it goes to the DONE state and prints an error message since ! is
        // not a valid operator on its own
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.ID.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.DIGIT.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.EQUAL.ordinal()] = state.NOT_EQUAL_CHECK;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.EXCLAMATION.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.STAR.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.NEWLINE.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.EOP.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.WHITESPACE.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill in the transition table for the BEGIN_COMMENT_CHECK state
        // if it encounters a *, it goes to the COMMENT state, and if it encounters
        // anything else, it goes to the ERROR state and prints an error message since /
        // is not a valid operator on its own
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.ID.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.DIGIT.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.LBRACE.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.RBRACE.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.LPAREN.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.RPAREN.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.STRING.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.PLUS.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.EQUAL.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.EXCLAMATION.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.SLASH.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.STAR.ordinal()] = state.COMMENT;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.NEWLINE.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.EOP.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.WHITESPACE.ordinal()] = state.ERROR;
        transitionTable[state.BEGIN_COMMENT_CHECK.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // fill transition table for the END_COMMENT_CHECK state
        // if it finds /, goes back to begin, if it finds anything else, goes back to
        // comment
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.ID.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.DIGIT.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.LBRACE.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.RBRACE.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.LPAREN.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.RPAREN.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.STRING.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.PLUS.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.EQUAL.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.EXCLAMATION.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.STAR.ordinal()] = state.END_COMMENT_CHECK;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.NEWLINE.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.EOP.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.WHITESPACE.ordinal()] = state.COMMENT;
        transitionTable[state.END_COMMENT_CHECK.ordinal()][characterType.OTHER.ordinal()] = state.COMMENT;

        // transition table for the EQUAL_CHECK state is handled in the EQUAL
        // check to see if it is a single or a double equals, if it is a double equals,
        // go to EQUAL state
        // Else it is a single equals and goes to DONE state
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.ID.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.DIGIT.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.LBRACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.RBRACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.LPAREN.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.RPAREN.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.STRING.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.PLUS.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.EQUAL.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.EXCLAMATION.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.SLASH.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.STAR.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.NEWLINE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.EOP.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.WHITESPACE.ordinal()] = state.DONE;
        transitionTable[state.EQUAL_CHECK.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // perform check for !=, if the ! is followde by = goes to done
        // if not it is an error since ! is not a valid operator on its own
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.ID.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.DIGIT.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.LBRACE.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.RBRACE.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.LPAREN.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.RPAREN.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.STRING.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.PLUS.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.EQUAL.ordinal()] = state.DONE;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.EXCLAMATION.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.SLASH.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.STAR.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.NEWLINE.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.EOP.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.WHITESPACE.ordinal()] = state.ERROR;
        transitionTable[state.NOT_EQUAL_CHECK.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // handle the EOP state, which is the end of the program, other characters
        // signify the start of a new program in the same file
        transitionTable[state.EOP.ordinal()][characterType.ID.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.DIGIT.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.LBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.RBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.LPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.RPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.STRING.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.PLUS.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.EQUAL.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.EXCLAMATION.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.SLASH.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.STAR.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.NEWLINE.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.EOP.ordinal()] = state.EOP;
        transitionTable[state.EOP.ordinal()][characterType.WHITESPACE.ordinal()] = state.BEGIN;
        transitionTable[state.EOP.ordinal()][characterType.OTHER.ordinal()] = state.ERROR;

        // the transition table for the ERROR state, which is an error state, so it goes
        // back to the BEGIN state for any character
        transitionTable[state.ERROR.ordinal()][characterType.ID.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.DIGIT.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.LBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.RBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.LPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.RPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.STRING.ordinal()] = state.ERROR;
        transitionTable[state.ERROR.ordinal()][characterType.PLUS.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.EQUAL.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.EXCLAMATION.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.SLASH.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.STAR.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.NEWLINE.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.EOP.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.WHITESPACE.ordinal()] = state.BEGIN;
        transitionTable[state.ERROR.ordinal()][characterType.OTHER.ordinal()] = state.BEGIN;

        // transition table for the DONE state, which is a done state, so it goes back
        // to the BEGIN state for any character
        transitionTable[state.DONE.ordinal()][characterType.ID.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.DIGIT.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.LBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.RBRACE.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.LPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.RPAREN.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.STRING.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.PLUS.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.EQUAL.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.EXCLAMATION.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.SLASH.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.STAR.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.NEWLINE.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.EOP.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.WHITESPACE.ordinal()] = state.BEGIN;
        transitionTable[state.DONE.ordinal()][characterType.OTHER.ordinal()] = state.BEGIN;

        // start at line 1, index 0, and program 1
        int currentLine = 1;
        int lastIndex = 0;
        int programNum = 1;

        // start with empty token
        String token = "";

        // start with current state at begin
        state currentState = state.BEGIN;

        // loop through the length of the program
        for (int currentIndex = 0; currentIndex < sourceCode.length(); currentIndex++) {

            // get the current character
            char c = sourceCode.charAt(currentIndex);

            // get the type of character at the current index
            characterType charType = getCharacterType(c);

            // set the next state to the previous state based on the transition table
            state nextState = transitionTable[currentState.ordinal()][charType.ordinal()];

            // keyword followed by ID print keyword start new token
            if (currentState == state.IDENTIFIER && charType == characterType.ID && isKeyword(token)) {
                nextState = state.DONE;
            }

            // start with DONE
            if (nextState == state.DONE) {
                // add current char to token when appropriate
                if (currentState == state.BEGIN) {
                    token += c;
                } else if ((currentState == state.EQUAL_CHECK || currentState == state.NOT_EQUAL_CHECK) && c == '=') {
                    token += c;
                }
                // switch statement for all possiblities within DONE
                switch (currentState) {
                    case IDENTIFIER:
                        // calls on the method to check if its a keyword, then print out
                        // either the keyword or the indiviual identifiers
                        checkKeyword(token, currentLine, lastIndex - token.length());
                        currentIndex--;
                        break;

                    case NUMBER:
                        // calls the log method and prints it out if verbose mode is on
                        log("LEXER: " + characterType.DIGIT + ": [ " + token + " ] number " + programNum + " at line "
                                + currentLine + ", col " + lastIndex);

                        // add the token to the list of tokens of type digit, with the value of the
                        // token, the line number, and the column number
                        tokens.add(new Token(characterType.DIGIT, token, currentLine, lastIndex - token.length()));
                        currentIndex--;
                        break;

                    case STRING:
                        // print that it is a string and at its location if verbose mode is on
                        log("LEXER: " + characterType.STRING + ": [ " + token + " ] at line " + currentLine + ", col "
                                + lastIndex);

                        // add the token to the list of tokens of type string, with the value of the
                        // token, the line number, and the column number
                        tokens.add(new Token(characterType.STRING, token, currentLine, lastIndex - token.length()));
                        break;

                    case EQUAL_CHECK:
                    case NOT_EQUAL_CHECK:
                    case EQUAL:
                        // if the length is 2, it is a Boolop so print that
                        if (token.length() == 2) {
                            log("LEXER: " + characterType.BOOLOP + ": [ " + token + " ] at line " + currentLine
                                    + ", col " + lastIndex);

                            // after the log, add the token to the list of tokens of type boolop, with the
                            // value of the token, the line number, and the column number
                            tokens.add(new Token(characterType.BOOLOP, token, currentLine, lastIndex - token.length()));
                        }

                        // if it is not a boolop, go back to the last spot so it can print out that
                        // character
                        else {
                            currentIndex--;
                            lastIndex--;
                            log("LEXER: " + getCharacterType(sourceCode.charAt(currentIndex)) + ": [ " + token
                                    + " ] at line " + currentLine + ", col " + lastIndex);

                            // after the log, add the token to the list of tokens of the appropriate type,
                            // token, the line number, and the column number
                            tokens.add(new Token(getCharacterType(sourceCode.charAt(currentIndex)), token, currentLine,
                                    lastIndex - token.length()));
                        }
                        lastIndex++;
                        break;

                    case COMMENT:
                    case BEGIN_COMMENT_CHECK:
                    case END_COMMENT_CHECK:

                        // do not print anything since comments are ignored by the lexer
                        break;
                    default:
                        // takes care of single token operators
                        log("LEXER: " + getCharacterType(sourceCode.charAt(currentIndex)) + ": [ " + token
                                + " ] at line " + currentLine + ", col " + lastIndex);

                        // after the log, add the token to the list of tokens of the appropriate type,
                        // token, the line number, and the column number
                        tokens.add(new Token(getCharacterType(sourceCode.charAt(currentIndex)), token, currentLine,
                                lastIndex - token.length()));
                        break;
                }
                // reset token and state
                token = "";
                currentState = state.BEGIN;
                continue;
            }

            // start with error handling
            if (nextState == state.ERROR) {
                // print that it is unknown character and where it was found at
                logError("LEXER: Unidentified Character [ " + sourceCode.charAt(currentIndex) + " ] at line "
                        + currentLine + ", col " + lastIndex);

                // add to the list if tokens of type other, with the value of the character, the
                // line number, and the column number
                // since this is an error, the token is the character itself is the token and
                // the program will stop
                tokens.add(new Token(characterType.OTHER, sourceCode.charAt(currentIndex) + "", currentLine,
                        lastIndex - token.length()));

                // reset token and state and move on and update counter
                hasErrors = true;
                errors++;
                lastIndex++;
                token = "";
                currentState = state.BEGIN;
                continue;
            }

            // handle string error very similar to error handling
            // stay in the STRING_ERROR state until the closing " is found and then it goes to begin
            if (nextState == state.STRING_ERROR) {
                // only print out the error message if the current state is STRING AND the next is the error
                if (currentState == state.STRING) {
                    // print that it is unknown character and where it was found at
                    logError("LEXER: Unidentified Character [ " + sourceCode.charAt(currentIndex) + " ] at line "
                            + currentLine + ", col " + lastIndex);

                    // add to the list if tokens of type other, with the value of the character, the line number, and the column number
                    // since this is an error, the token is the character itself is the token and

                    tokens.add(new Token(characterType.OTHER, sourceCode.charAt(currentIndex) + "", currentLine,
                            lastIndex - token.length()));

                    // reset token and state and move on and update counter
                    hasErrors = true;
                    errors++;
                }
                // reset token and increment
                lastIndex++;
                token = "";

                // if theres a new line, reset line index if no new last index
                if (c == '\n') {
                    currentLine++;
                    lastIndex = 0;
                } else {
                    lastIndex++;
                }
                // stay in this state until " is found
                currentState = state.STRING_ERROR;
                continue;
            }

                // if the next state is begin and current state is strong error it means a " is next set the current state to begin
                if(nextState == state.BEGIN && currentState == state.STRING_ERROR) {
                    if (c == '\n') {
                        currentLine++;
                        lastIndex = 0;
                    } else {
                        lastIndex++;
                    }
                    // set the state equal to begin so that it can print the next character
                    currentState = state.BEGIN;
                }
            

            // handle end of program
            if (nextState == state.EOP) {
                // log message
                log("LEXER: " + characterType.EOP + ": [ $ ] number " + programNum + " at line " + currentLine
                        + ", col " + lastIndex);

                // add to the list if tokens of type eop, with the value of $, the line number,
                // and the column number
                tokens.add(new Token(characterType.EOP, "$", currentLine, lastIndex - token.length()));

                // Reset state, update line and index for the new program
                programNum++;
                lastIndex++;
                token = "";
                currentState = state.BEGIN;

                continue;
            }

            // if in comment state, do not add to token and do not print anything just
            // increment the index and continue
            if (currentState == state.COMMENT) {

                // if theres a new line, update the line number and reset the index for the new
                // line
                if (c == '\n') {
                    currentLine++;
                    lastIndex = 0;
                } else {
                    lastIndex++;
                }
                // move to the next state
                currentState = nextState;
                continue;
            }

            // if not in begin state, add to the token
            if (currentState != state.BEGIN) {
                // add to the token
                if (currentState != state.COMMENT && currentState != state.BEGIN_COMMENT_CHECK
                        && currentState != state.END_COMMENT_CHECK) {
                    token += c;
                }
                // if theres a new line, update the line number and reset the index for the new
                // line
                if (c == '\n') {
                    currentLine++;
                    lastIndex = 0;
                }
                // or just increment the index for the next character
                else {
                    lastIndex++;
                }

                currentState = nextState;
                continue;
            }

            // we're in BEGIN and nextState is BEGIN don't add whitespcae and newline to
            // token
            if (nextState == state.BEGIN) {
                if (c == '\n') {
                    currentLine++;
                    lastIndex = 0;
                } else {
                    lastIndex++;
                }
                continue;
            }

            // we're in BEGIN, transitioning to a building state
            // add char and update state so we can start building the token
            // don't add opening quote for strings, or / for comments
            if (nextState != state.BEGIN_COMMENT_CHECK && nextState != state.STRING) {
                token += c;
            }
            if (c == '\n') {
                currentLine++;
                lastIndex = 0;
            } else {
                lastIndex++;
            }
            currentState = nextState;
        }
        // return the list of tokens
        return tokens;
    }

    public boolean lexErrors() {
        // check for lexing errors
        if (hasErrors) {
            logError("Stopping program ... " + errors + " error(s) found.");
        }
        return hasErrors;
    }

    public void getWarnings() {
        // check for any warnings, such as unused variables or unreachable code
        // for now, just print a message saying that there are no warnings
        log(warnings + " warning(s) found.");
    }

    public Lex.characterType getCharacterType(char c) {
        if (Character.isLowerCase(c)) {
            return characterType.ID;
        } else if (Character.isDigit(c)) {
            return characterType.DIGIT;
        } else if (c == '{') {
            return characterType.LBRACE;
        } else if (c == '}') {
            return characterType.RBRACE;
        } else if (c == '(') {
            return characterType.LPAREN;
        } else if (c == ')') {
            return characterType.RPAREN;
        } else if (c == '"') {
            return characterType.STRING;
        } else if (c == '+') {
            return characterType.PLUS;
        } else if (c == '=') {
            return characterType.EQUAL;
        } else if (c == '!') {
            return characterType.EXCLAMATION;
        } else if (c == '/') {
            return characterType.SLASH;
        } else if (c == '*') {
            return characterType.STAR;
        } else if (c == '\n') {
            return characterType.NEWLINE;
        } else if (c == '$') {
            return characterType.EOP;
        } else if (c == ' ' || c == '\t' || c == '\r') {
            return characterType.WHITESPACE;
        } else {
            return characterType.OTHER;
        }
    }

    // maps keyword type string from KEYWORDS to the corresponding characterType for
    // token emission
    // if it is not a keyword, it will throw an error
    private characterType getKeywordTokenType(String keywordType) {
        return switch (keywordType) {
            case "print" -> characterType.PRINT;
            case "loop" -> characterType.LOOP;
            case "if" -> characterType.IF;
            case "Type" -> characterType.TYPE;
            case "BoolVal" -> characterType.BOOLVAL;
            default -> throw new IllegalArgumentException("Unknown keyword type: " + keywordType);
        };
    }

    // takes in the token, checks to see if it is a keyword using regex, and returns
    // the boolean if it is or isn't
    private boolean isKeyword(String lexeme) {
        return KEYWORD_PATTERN.matcher(lexeme).matches();
    }

    // checks if tokens are a keyword, if they are it sets it to the keyword
    // if it doesn't it saves it as a character at the origional location
    // if a longer keyword is found it saves it as that
    // also prints out what type of keyword it is
    // takes in the token, the line, and the starting location of the token
    private void checkKeyword(String token, int line, int startCol) {
        int i = 0;
        // loop through the token
        while (i < token.length()) {
            String longestKeyword = null;
            String longestKeywordType = null;
            // check to see if it is a keyword
            for (String[] kw : KEYWORDS) {
                String keyword = kw[0];
                String keywordType = kw[1];
                // if it is a keyword, set the keyword and keyword type so that it can be
                // printed out
                if (token.regionMatches(i, keyword, 0, keyword.length())
                        && (longestKeyword == null || keyword.length() > longestKeyword.length())) {
                    longestKeyword = keyword;
                    longestKeywordType = keywordType;
                }
            }
            // if longestKeyword isn't null print out the type and keyword
            if (longestKeyword != null) {
                log("LEXER: " + longestKeywordType + ": [ " + longestKeyword + " ] at line " + line + ", col "
                        + (startCol + i));

                // add the token with the specific keyword type (PRINT, LOOP, IF, TYPE, BOOLVAL)
                tokens.add(new Token(getKeywordTokenType(longestKeywordType), longestKeyword, line, startCol + i));

                i += longestKeyword.length();
                // it is not a keyword, print out the identifier
            } else {
                log("LEXER: " + getCharacterType(token.charAt(i)) + ": [ " + token.charAt(i) + " ] at line " + line
                        + ", col " + (startCol + i));

                // since it is not a keyword, add the token to the list of tokens of the
                // appropriate type, identifier, with the value of the character at the current
                // index, the line number, and the column number
                tokens.add(new Token(characterType.ID, token.charAt(i) + "", line, startCol + i));
                i++;
            }
        }
    }

}
