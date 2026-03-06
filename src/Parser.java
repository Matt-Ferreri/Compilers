import java.util.List;

public class Parser {

    // list of tokens and the current position in the token stream
    private List<Token> tokens;
    private int current;

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
        // set the verbose, tokens, and current position
        this.verbose = isVerbose;
        this.tokens = tokens;
        this.current = 0;

        // perform recursive descent, start at the outmost part "Program"
        parseProgram();
    }

    // it takes in the expected charcater type and compares it to the current token
    // type
    private void match(Lex.characterType expected) {

        // if the current index is greater than the size of the tokens, print an error
        if (current >= tokens.size()) {
            logError("PARSER: Expected " + expected + " but got " + tokens.get(current).tokenType
                    + " at line " + tokens.get(current).line + " and column "
                    + tokens.get(current).position);
            hasErrors = true;
            errors++;
            return;
        }
        // if the current token type is the expected type, consume the token and return
        if (tokens.get(current).tokenType == expected) {
            current++; // consume the token
            return;
        }
        // if the current token type is not the expected type, print an error
        logError("PARSER: Expected " + expected + " but got " + tokens.get(current).tokenType
                + " at line " + tokens.get(current).line + " and column "
                + tokens.get(current).position);
        hasErrors = true;
        errors++;
    }

    // a program is a Block + $
    private void parseProgram() {
        // a program is a Block + $
        parseBlock();
        // the program must end with $
        match(Lex.characterType.EOP);
    }

    // a parseBlock is made up of {statementList}
    private void parseBlock() {
        // the block must start with Left Brace
        match(Lex.characterType.LBRACE);
        // block is made up of a statementList
        parseStatementList();
        // the block must end with Right Brace
        match(Lex.characterType.RBRACE);
    }

    // statement list is made up of a Statement StatementList OR a null
    private void parseStatementList() {
        Lex.characterType currentToken = tokens.get(current).tokenType;
        // if the next character is the statement, recursively call statement followed
        // by statement list, if not, do nothing
        if (currentToken == Lex.characterType.PRINT || currentToken == Lex.characterType.IF ||
                currentToken == Lex.characterType.LOOP || currentToken == Lex.characterType.TYPE ||
                currentToken == Lex.characterType.ID || currentToken == Lex.characterType.LBRACE) {
            parseStatement();
            parseStatementList();
        }
        // if it isn't a statement followed by a statement list, do nothing
        else {
            // do nothing
            // its a E production
        }
    }

    // statement can be either a print, assugnment, varDec, while, if, or block
    // must handle each case
    private void parseStatement() {
        Lex.characterType currentToken = tokens.get(current).tokenType;

        // if the current character is print, go to parsePrintStatement
        if (currentToken == Lex.characterType.PRINT) {
            parsePrintStatement();
        }

        // if its an ID, go to parseAssignmentStatement because a variable is being
        // assigned something
        else if (currentToken == Lex.characterType.ID) {
            parseAssignmentStatment();
        }

        // if its of type Type, go to parseVarDeclStatement because a new variable is
        // being declared
        else if (currentToken == Lex.characterType.TYPE) {
            parseVarDecl();
        }

        // if its of type loop, go to parseWhileStatement because it is the start of a
        // loop
        else if (currentToken == Lex.characterType.LOOP) {
            parseWhileStatement();
        }

        // if its of type IF, go to parseIfStatement because it starts an IF statement
        else if (currentToken == Lex.characterType.IF) {
            parseIfStatement();
        }

        // if its a left brace, go to parseBlock 
        else if (currentToken == Lex.characterType.LBRACE) {
            parseBlock();
        }

        // if its none of those, print an error
        else {
            logError("PARSER: Expected a statement but got " + currentToken + " at line " + tokens.get(current).line + " and column " + tokens.get(current).position);
            hasErrors = true;
            errors++;
        }
    }

    // a print statement is the keyword print, followed by (, an expression, then a
    // )
    private void parsePrintStatement() {
        // match the print statement
        match(Lex.characterType.PRINT);
        // then match the left paren
        match(Lex.characterType.LPAREN);
        // parse the expression
        parseExpr();
        // match the right paren
        match(Lex.characterType.RPAREN);
    }

    // an assignmentStatement is an ID, followed by an equal, followed by an Expr
    private void parseAssignmentStatment() {
        // starts with an ID
        parseID();
        // next must match an equal sign
        match(Lex.characterType.EQUAL);
        // then an expr
        parseExpr();
    }

    // a varDecl is a type followed by an ID
    private void parseVarDecl() {
        // first check for type
        parseType();
        // then check for an ID
        parseID();
    }

    // a whileStatement first must match "while", then is a BooleanExpr, then a
    // block
    private void parseWhileStatement() {
        // first match the word while, which is labeled loop
        match(Lex.characterType.LOOP);
        // next check for a BooleanExpr
        parseBooleanExpr();
        // ends with a Block
        parseBlock();
    }

    // an ifstatment starts with "if", then is a BooleanExpr, then a block
    private void parseIfStatement() {
        // first match the word IF
        match(Lex.characterType.IF);
        // next check for a BooleanExpr
        parseBooleanExpr();
        // ends with a Block
        parseBlock();
    }

    // an expr is either an IntExpr, a StringExpr, a BooleanExpr, or an ID
    private void parseExpr() {
        Lex.characterType currentToken = tokens.get(current).tokenType;
        // if the character is a digit, go into parseIntExpr
        if (currentToken == Lex.characterType.DIGIT) {
            parseIntExpr();
        }
        // if it is a quote, go to parseStringExpr
        else if (currentToken == Lex.characterType.STRING) {
            parseStringExpr();
        }
        // if it is a left paren go to parseBooleanExpr or if its a character type BoolVal
        else if (currentToken == Lex.characterType.LPAREN || currentToken == Lex.characterType.BOOLVAL) {
            parseBooleanExpr();
        }
        // if it is an ID, go to parseID
        else if (currentToken == Lex.characterType.ID) {
            parseID();
        }
    }

    // in parseIntExpr, always go to digit, then check if the next character is a
    // plus
    private void parseIntExpr() {

        // first consume the digit
        parseDigit();

        // get the type of token after the digit is consumed
        Lex.characterType currentToken = tokens.get(current).tokenType;

        // next, if the next character is a PLUS, do parseIntop then parse Expr
        // if it isn't do nothing
        if (currentToken == Lex.characterType.PLUS) {
            // do parseIntop then parseExpr
            parseIntop();
            parseExpr();
        }
    }

    // a StringExpr is a quote followed by a CharList, followed by a quote
    // a string token is a "" around the string, only need to match to a string
    private void parseStringExpr() {
        // match the string since a string is a " with words, then a "
        match(Lex.characterType.STRING);
    }

    // a BooleanExpr is a ( an expression, a boolop, another expression, then a )
    // or it is just a boolval
    // check first character, if its a ( do the first, if its a boolval just
    // parseBoolVal
    private void parseBooleanExpr() {
        Lex.characterType currentToken = tokens.get(current).tokenType;

        if (currentToken == Lex.characterType.LPAREN) {
            // first match the first (
            match(Lex.characterType.LPAREN);
            // next it has an Expr
            parseExpr();
            // then it has a boolop
            parseBoolOp();
            // has another Expr
            parseExpr();
            // ends with a ) so match that
            match(Lex.characterType.RPAREN);
        }

        else if (currentToken == Lex.characterType.BOOLVAL) {
            parseBoolVal(); // if its a boolval just do parseBoolVal
        }
    }

    // an ID is a char, so do parseChar
    private void parseID() {
        // go to parseChar since that is the only thing Id does
        parseChar();
    }

    // a charList is either a char followed by a Charlist, a space followed by a
    // Charlist, or nothing
    private void parseCharList() {
        // get the type of the current token
        Lex.characterType currentToken = tokens.get(current).tokenType;

        // if current token is a char, do parseChar followed by parseCharList
        if (currentToken == Lex.characterType.ID) {
            // first parseChar then parseCharList
            parseChar();
            parseCharList();
        }
        // spaces get discarded in the lexer so they do not need to be checked for
        // if it is a character or a space (since discarded) it will contine, or else
        // itll do nothing

        // else nothing happens and its a ɛ production
        else {
            // nothing
            // it’s a ɛ
            // production
        }
    }

    // in parseType match to the type type
    private void parseType() {
        match(Lex.characterType.TYPE);
    }

    // in parseChar match to the type ID
    private void parseChar() {
        match(Lex.characterType.ID);
    }

    // in parseDigit, must match type DIGIT
    private void parseDigit() {
        match(Lex.characterType.DIGIT);
    }

    // in parseBoolOp, must be either == or !=
    private void parseBoolOp() {
        match(Lex.characterType.BOOLOP);
    }

    // must be match true or false, so match a BOOLVAL
    private void parseBoolVal() {
        match(Lex.characterType.BOOLVAL);
    }

    // must match the plus symbol
    private void parseIntop() {
        match(Lex.characterType.PLUS);
    }
}
