import java.util.List;

public class Parser {

    // thrown to stop parsing the current program as soon as an error is found
    private static class ParseErrorException extends RuntimeException {}

    // list of tokens and the current position in the token stream
    private List<Token> tokens;
    private int current;
    // start with no errors or warnings
    boolean hasErrors = false;
    boolean hasWarnings = false;
    int warnings = 0;

    // if we get an error, set this so we only report it once for this program
    private boolean currentProgramHasErrors = false;

    private boolean verbose;

    // create a new tree
    Tree tree = new Tree();
    // takes message in as a variable and and always prints message
    private void logError(String message) {
        System.out.println(message);
        System.out.println("Errors found! Stopping current program.");
        return;
    }


    // if there is an error return true
    public boolean parseErrors() {
        return hasErrors;
    }

    // runs the parser on one program's token list
    public Tree run(List<Token> tokens, boolean isVerbose) {
        // set the verbose, tokens, and current position
        this.verbose = isVerbose;
        this.tokens = tokens;
        this.current = 0;
        this.hasErrors = false;
        this.currentProgramHasErrors = false;
        this.tree = new Tree();

        // start the parse for just this program
        performParse();

        // return the cst
        return tree;
    }

    public void performParse() {
        try {
            parseProgram();
            if (verbose) {
                System.out.print(tree);
            }
        } catch (ParseErrorException e) {
            hasErrors = true;
        }
    }

    // it takes in the expected charcater type and compares it to the current token type
    private void match(Lex.characterType expected) {
        // ensure current isn't bigger than the size of the token stream
        if (current >= tokens.size()) {
            // Only report errors once per program
            if (!currentProgramHasErrors) {
                logError("PARSER: Unexpected end of input.");
                // set the error flags for main and current program to true
                hasErrors = true;
                currentProgramHasErrors = true;
            }
            throw new ParseErrorException();
        }

        // if the current token type is the expected type, consume the token and return
        if (tokens.get(current).tokenType == expected) {
            // add the value of the token to the tree of type leaf
            tree.addNode(tokens.get(current).value, "leaf");
            current++; // consume the token
            return;
        }


        // if the current token type is not the expected type, print an error
        logError("PARSER: Expected " + expected + " but got " + tokens.get(current).tokenType
                + " at line " + tokens.get(current).line + " and column "
                + tokens.get(current).position);
        // set the error flags for main and current program to true
        hasErrors = true;
        currentProgramHasErrors = true;

        throw new ParseErrorException();
    }

    // helper kept for older call sites - now it simply aborts the current parse
    private void goToNextProgram() {
        throw new ParseErrorException();
    }

    // a program is a Block + $
    private void parseProgram() {
        // add the program to the CST
        tree.addNode("Program", "branch");

        // a program is a Block + $
        parseBlock();
        // the program must end with $
        match(Lex.characterType.EOP);
    }

    // a parseBlock is made up of {statementList}
    private void parseBlock() {
        // add the block to the CST
        tree.addNode("Block", "branch");

        // the block must start with Left Brace
        match(Lex.characterType.LBRACE);
        // block is made up of a statementList
        parseStatementList();
        // the block must end with Right Brace
        match(Lex.characterType.RBRACE);

        // move up in the tree
        tree.endChildren();
    }

    // statement list is made up of a Statement StatementList OR a null
    private void parseStatementList() {
        // add StatementList to CST
        tree.addNode("StatementList", "branch");
        if (current >= tokens.size()) {
            tree.endChildren();
            return;
        }

        Lex.characterType currentToken = tokens.get(current).tokenType;
        // if the next character is the statement, recursively call statement followed
        // by statement list, if not, do nothing
        if (currentToken == Lex.characterType.PRINT || currentToken == Lex.characterType.IF ||
                currentToken == Lex.characterType.WHILE || currentToken == Lex.characterType.TYPE ||
                currentToken == Lex.characterType.ID || currentToken == Lex.characterType.LBRACE) {
            parseStatement();
            parseStatementList();

        }
        // if it isn't a statement followed by a statement list, do nothing
        else if (currentToken == Lex.characterType.RBRACE){
            // do nothing
            // its a E production
        }
        // if it is not a statement followed by a statement list or a right brace, print an error
        else{
            logError("PARSER: Expected a statement or a right brace but got " + currentToken + " at line " + tokens.get(current).line + " and column " + tokens.get(current).position);
            hasErrors = true;
            currentProgramHasErrors = true;
            goToNextProgram();
        }
        // move up in the tree no matter what
        tree.endChildren();
    }

    // statement can be either a print, assugnment, varDec, while, if, or block
    // must handle each case
    private void parseStatement() {
        // add Statement to CST
        tree.addNode("Statement", "branch");
        if (current >= tokens.size()) {
            tree.endChildren();
            return;
        }
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
        else if (currentToken == Lex.characterType.WHILE) {
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

        // move up in the tree
        tree.endChildren();
    }

    // a print statement is the keyword print, followed by (, an expression, then a
    // )
    private void parsePrintStatement() {
        // add PrintStatement to CST
        tree.addNode("PrintStatement", "branch");

        // match the print statement
        match(Lex.characterType.PRINT);
        // then match the left paren
        match(Lex.characterType.LPAREN);
        // parse the expression
        parseExpr();
        // match the right paren
        match(Lex.characterType.RPAREN);

        // move up in the tree
        tree.endChildren();
    }

    // an assignmentStatement is an ID, followed by an equal, followed by an Expr
    private void parseAssignmentStatment() {
        // add AssignmentStatement to CST
        tree.addNode("AssignmentStatement", "branch");

        // starts with an ID
        parseID();
        // next must match an equal sign
        match(Lex.characterType.EQUAL);
        // then an expr
        parseExpr();

        // move up in the tree
        tree.endChildren();
    }

    // a varDecl is a type followed by an ID
    private void parseVarDecl() {
        // add VarDecl to CST
        tree.addNode("VarDecl", "branch");

        // first check for type
        parseType();
        // then check for an ID
        parseID();

        // move up in the tree
        tree.endChildren();
    }

    // a whileStatement first must match "while", then is a BooleanExpr, then a
    // block
    private void parseWhileStatement() {
        // add WhileStatement to CST
        tree.addNode("WhileStatement", "branch");

        // first match the word while, which is labeled loop
        match(Lex.characterType.WHILE);
        // next check for a BooleanExpr
        parseBooleanExpr();
        // ends with a Block
        parseBlock();

        // move up in the tree
        tree.endChildren();
    }

    // an ifstatment starts with "if", then is a BooleanExpr, then a block
    private void parseIfStatement() {
        // add IfStatement to CST
        tree.addNode("IfStatement", "branch");

        // first match the word IF
        match(Lex.characterType.IF);
        // next check for a BooleanExpr
        parseBooleanExpr();
        // ends with a Block
        parseBlock();

        // move up in the tree
        tree.endChildren();
    }

    // an expr is either an IntExpr, a StringExpr, a BooleanExpr, or an ID
    private void parseExpr() {
        if (current >= tokens.size())
            return;

        // add Expr to CST
        tree.addNode("Expr", "branch");

        Lex.characterType currentToken = tokens.get(current).tokenType;
        // if the character is a digit, go into parseIntExpr
        if (currentToken == Lex.characterType.DIGIT) {
            parseIntExpr();
        }
        // if it is a quote, go to parseStringExpr
        else if (currentToken == Lex.characterType.STRING) {
            parseStringExpr();
        }
        // if it is a left paren go to parseBooleanExpr or if its a character type
        // BoolVal
        else if (currentToken == Lex.characterType.LPAREN || currentToken == Lex.characterType.BOOLVAL) {
            parseBooleanExpr();
        }
        // if it is an ID, go to parseID
        else if (currentToken == Lex.characterType.ID) {
            parseID();
        }
        // if it is not an ID, IntExpr, StringExpr, BooleanExpr, or LPAREN, print an error
        else{
            logError("PARSER: Expected an ID, IntExpr, StringExpr, BooleanExpr, or LPAREN but got " + currentToken + " at line " + tokens.get(current).line + " and column " + tokens.get(current).position);
            hasErrors = true;
            currentProgramHasErrors = true;
            goToNextProgram();
        }

        // move up in the tree
        tree.endChildren();
    }

    // in parseIntExpr, always go to digit, then check if the next character is a
    // plus
    private void parseIntExpr() {
        // add IntExpr to CST
        tree.addNode("IntExpr", "branch");

        // first consume the digit
        parseDigit();

        // get the type of token after the digit is consumed
        if (current >= tokens.size()) {
            tree.endChildren();
            return;
        }
        Lex.characterType currentToken = tokens.get(current).tokenType;

        // next, if the next character is a PLUS, do parseIntOp then parse Expr
        // if it isn't do nothing
        if (currentToken == Lex.characterType.PLUS) {
            // do parseIntOp then parseExpr
            parseIntOp();
            parseExpr();
        }

        // move up in the tree
        tree.endChildren();
    }

    // a StringExpr is a quote followed by a CharList, followed by a quote
    // a string token is a "" around the string, only need to match to a string
    private void parseStringExpr() {
        // add StringExpr to CST
        tree.addNode("StringExpr", "branch");

        // opening quote
        match(Lex.characterType.STRING);
        // contents of the string
        parseCharList();
        // closing quote
        match(Lex.characterType.STRING);

        // move up in the tree
        tree.endChildren();
    }

    // a BooleanExpr is a ( an expression, a boolop, another expression, then a )
    // or it is just a boolval
    // check first character, if its a ( do the first, if its a boolval just
    // parseBoolVal
    private void parseBooleanExpr() {
        // add BooleanExpr to CST
        tree.addNode("BooleanExpr", "branch");
        if (current >= tokens.size()) {
            tree.endChildren();
            return;
        }

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

        // move up in the tree
        tree.endChildren();
    }

    // an ID is a char, so do parseChar
    private void parseID() {
        // add ID to CST
        tree.addNode("ID", "branch");

        // go to parseChar since that is the only thing Id does
        parseChar();

        // move up in the tree
        tree.endChildren();
    }

    // a charList is either a char followed by a Charlist, a space followed by a
    // Charlist, or nothing
    private void parseCharList() {
        // add CharList to CST
        tree.addNode("CharList", "branch");
        if (current >= tokens.size()) {
            tree.endChildren();
            return;
        }

        // get the type of the current token
        Lex.characterType currentToken = tokens.get(current).tokenType;

        // if current token is a char, do parseChar followed by parseCharList
        if (currentToken == Lex.characterType.ID) {
            // first parseChar then parseCharList
            parseChar();
            parseCharList();
        }
        // if current token is a space, consume it and continue
        else if (currentToken == Lex.characterType.WHITESPACE) {
            match(Lex.characterType.WHITESPACE);
            parseCharList();
        }

        // else nothing happens and its a ɛ production
        else {
            // nothing
            // it’s a ɛ
            // production
        }

        // move up in the tree
        tree.endChildren();
    }

    /*
     * since all of the following are matching to a token
     * we are adding leaves to the tree for each token.
     * we do not need to add any nodes since match already adds the
     * value of the token to the tree of type leaf
     */
    // in parseType match to the type type
    private void parseType() {
        // match the type
        match(Lex.characterType.TYPE);

    }

    // in parseChar match to the type ID
    private void parseChar() {
        // match the digit
        match(Lex.characterType.ID);

    }

    // in parseDigit, must match type DIGIT
    private void parseDigit() {
        match(Lex.characterType.DIGIT);

    }

    // in parseBoolOp, must be either == or !=
    private void parseBoolOp() {
        // match the boolean operator
        match(Lex.characterType.BOOLOP);

    }

    // must be match true or false, so match a BOOLVAL
    private void parseBoolVal() {
        // match the boolean value
        match(Lex.characterType.BOOLVAL);
    }

    // must match the plus symbol
    private void parseIntOp() {
        // match the plus sign
        match(Lex.characterType.PLUS);
    }
}
