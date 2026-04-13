import java.util.List;
import java.util.Hashtable;

public class SemanticAnalyzer {

    // if we get an error, set this to true a new program is reached
    private boolean currentProgramHasErrors = false;

    private boolean verbose;
    public boolean hasErrors = false;

    int currentScope = 0; // start at global scope, will move with brackets
    int current = 0; // current token position while building AST / symbol table
    int errors = 0;

    Tree symbolTable = new Tree(); // a tree of hash tables for the symbol table
    Tree AST = new Tree(); // a tree of the AST
    Hashtable<String, Hashtable<String, Symbol>> scopes = new Hashtable<>();
    Hashtable<Integer, Integer> parentScope = new Hashtable<>(); // maps each scope to its parent

    // list of tokens for the semantic analyzer,
    // since we made it this far we have no errors, in lexing or parsing 
    // so we can just use the list of tokens to perform semantic analysis
    private List<Token> tokens; 
    private Tree cst;

    // if there is an error return true
    public boolean semanticErrors() {
        // if we have any errors, return true
        if (errors > 0) {
            return true;
        }
        return false;
    }

    public void run(List<Token> tokens, Tree cst) {
        this.tokens = tokens;
        this.currentScope = 0;
        this.hasErrors = false;
        this.symbolTable = new Tree();
        this.scopes = new Hashtable<>();
        this.parentScope = new Hashtable<>();
        this.cst = cst;

        // reduce the CST down to just the meaningful AST nodes
        AST = new Tree();
        createASTFromCST();
        checkScopeAndTypes(tokens);
        return;
    }

    public void printAST() {
        System.out.println("\n--- Abstract Syntax Tree ---");
        System.out.println(AST.toString());
        System.out.println("---------------------------\n");
    }

    public void printSymbolTable() {
        System.out.println("\n--- Symbol Table ---");
        for (String scopeKey : scopes.keySet()) {
            System.out.println("Scope " + scopeKey + ":");
            Hashtable<String, Symbol> scopeTable = scopes.get(scopeKey);
            for (String varName : scopeTable.keySet()) {
                Symbol sym = scopeTable.get(varName);
                System.out.println("  " + varName + " | type: " + sym.type
                        + " | initialized: " + sym.isInitialized
                        + " | used: " + sym.isUsed);
            }
        }
        System.out.println("--------------------");
    }

    public void createASTFromCST() {
        if (cst != null && cst.getRoot() != null) {
            reduceNode(cst.getRoot());
        }
    }

    private void reduceNode(Tree.Node node) {
        if (node == null) {
            return;
        }

        switch (node.name) {
            case "Program" -> reduceProgram(node);
            case "Block" -> reduceBlock(node);
            case "StatementList" -> reduceStatementList(node);
            case "Statement" -> reduceStatement(node);
            case "PrintStatement" -> reducePrintStatement(node);
            case "AssignmentStatement" -> reduceAssignmentStatement(node);
            case "VarDecl" -> reduceVarDecl(node);
            case "WhileStatement" -> reduceWhileStatement(node);
            case "IfStatement" -> reduceIfStatement(node);
            case "Expr" -> reduceExpr(node);
            case "ID" -> AST.addNode(extractLeafValue(node), "leaf");
            default -> {
                if (node.children != null) {
                    for (Tree.Node child : node.children) {
                        reduceNode(child);
                    }
                }
            }
        }
    }

    // add a Program root node so the outer Block has a parent to return to
    private void reduceProgram(Tree.Node node) {
        AST.addNode("Program", "branch");
        for (Tree.Node child : node.children) {
            if ("Block".equals(child.name)) {
                reduceBlock(child);
            }
        }
    }

    // if it is a block, we need to add block to the AST then go to the children
    private void reduceBlock(Tree.Node node) {
        AST.addNode("Block", "branch");
        // go to each of the children of the block and reduce them
        for (Tree.Node child : node.children) {
            if ("StatementList".equals(child.name)) {
                reduceStatementList(child);
            }
        }
        AST.endChildren();
    }

    // if it is a statement list, we DON'T need to add it to the AST, but still go
    // the the children
    private void reduceStatementList(Tree.Node node) {
        // go to each of the children of the statement list and reduce them
        for (Tree.Node child : node.children) {
            if ("Statement".equals(child.name)) {
                reduceStatement(child);
            } else if ("StatementList".equals(child.name)) {
                reduceStatementList(child);
            }
        }
    }

    // if it is a statement, we just need to go to the children and reduce them,
    // dont add to AST
    private void reduceStatement(Tree.Node node) {
        for (Tree.Node child : node.children) {
            reduceNode(child);
        }
    }

    // if it is a print statement, we need to add print statement to the AST then go
    // to the children
    private void reducePrintStatement(Tree.Node node) {
        AST.addNode("PrintStatement", "branch");
        for (Tree.Node child : node.children) {
            if ("Expr".equals(child.name)) {
                reduceExpr(child);
            }
        }
        // move back up the AST once we are done with the children
        AST.endChildren();
    }

    // if it is an assignment statement, we need to add assignment statement to the
    // AST then go to the children
    private void reduceAssignmentStatement(Tree.Node node) {
        AST.addNode("AssignmentStatement", "branch");
        for (Tree.Node child : node.children) {
            // add the ID to the AST as a leaf node
            if ("ID".equals(child.name)) {
                AST.addNode(extractLeafValue(child), "leaf");
            } else if ("Expr".equals(child.name)) {
                reduceExpr(child);
            }
        }
        // move back up the AST once we are done with the children
        AST.endChildren();
    }

    // if it is a variable declaration, we need to add variable declaration to the
    // AST then go to the children
    private void reduceVarDecl(Tree.Node node) {
        AST.addNode("VarDecl", "branch");
        // add the ID to the AST as a leaf node and the type as a leaf node
        for (Tree.Node child : node.children) {
            if ("ID".equals(child.name)) {
                AST.addNode(extractLeafValue(child), "leaf");
            } else if (isLeaf(child)) {
                AST.addNode(child.name, "leaf");
            }
        }
        // move back up the AST once we are done with the children
        AST.endChildren();
    }

    // if it is a while statement, we need to add while statement to the AST then go
    // to the children
    private void reduceWhileStatement(Tree.Node node) {
        AST.addNode("WhileStatement", "branch");
        for (Tree.Node child : node.children) {
             if ("Block".equals(child.name)) {
                reduceBlock(child);
            }
        }
        // move back up the AST once we are done with the children
        AST.endChildren();
    }

    // if it is an if statement, we need to add if statement to the AST then go to
    // the children
    private void reduceIfStatement(Tree.Node node) {
        AST.addNode("IfStatement", "branch");
        for (Tree.Node child : node.children) {
        if ("Block".equals(child.name)) {
                reduceBlock(child);
            }
        }
        // move back up the AST once we are done with the children
        AST.endChildren();
    }

    // if it is an expression, we need to go to the children and reduce them, dont
    // add to AST
    private void reduceExpr(Tree.Node node) {
        for (Tree.Node child : node.children) {
            if ("ID".equals(child.name)) {
                // only add the ID to the AST as a leaf node
                AST.addNode(extractLeafValue(child), "leaf");
            }
        }
    }


    private boolean isLeaf(Tree.Node node) {
        return node.children == null || node.children.isEmpty();
    }

    private String extractLeafValue(Tree.Node node) {
        if (isLeaf(node)) {
            return node.name;
        }
        for (Tree.Node child : node.children) {
            String value = extractLeafValue(child);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    public void checkScopeAndTypes(List<Token> tokens) {
        int activeScope = -1; // no scope until we enter the first block


        for (int i = 0; i < tokens.size(); i++) {
                            
            // reset hasErrors to false before each token
            hasErrors = false;

            Token token = tokens.get(i);
            if (token.tokenType == Lex.characterType.LBRACE) {
                // add a new node in the symbol table
                symbolTable.addNode(String.valueOf(currentScope), "branch");

                // record that the new scope's parent is the current activeScope
                parentScope.put(currentScope, activeScope);

                activeScope = currentScope;

                // create the hashtable for this scope so we can store symbols in it
                scopes.put(String.valueOf(activeScope), new Hashtable<>());

                // increment the current scope
                currentScope++;
            } else if (token.tokenType == Lex.characterType.RBRACE) {
                // only move up in the symbol table if we're not already at the root scope
                if (parentScope.getOrDefault(activeScope, -1) >= 0) {
                    symbolTable.endChildren();
                }
                // follow the parent chain back instead of just decrementing
                activeScope = parentScope.getOrDefault(activeScope, -1);
            }

            // variable declarations
            else if (token.tokenType == Lex.characterType.TYPE) {
                // declarations are TYPE followed by ID, store them in the current scope table
                if (tokens.get(i + 1).tokenType == Lex.characterType.ID && activeScope >= 0) {
                    // create a symbol for the variable and add it to the current scope in the
                    // symbol table
                    // the symbol should store the type of the variable, whether it is initialized,
                    // and whether it is used, they both start as false
                    Symbol symbol = new Symbol(token.value, false, false);
                    scopes.get(String.valueOf(activeScope)).put(tokens.get(i + 1).value, symbol);
                    i++; // skip the ID token since we already processed it
                }
            }

            // assignment statements: check for scope and type
            // only treat as assignment if the next token is the assign operator
            else if (token.tokenType == Lex.characterType.ID
                    && tokens.get(i + 1).tokenType == Lex.characterType.EQUAL) { 
                int currentScope = activeScope;
                // return the boolean value of whether the variable is in scope or not, if it is
                // not in scope, we have an error
                boolean inScope = checkScope(currentScope, token.value);

                // if we have gone through all the scopes and we still haven't found the
                // variable, then it is not in scope and we have an error
                if (!inScope) {
                    hasErrors = true;
                    errors++;
                    System.out.println("Error: Variable " + token.value + " is not in scope at line " + token.line
                            + " position " + token.position);
                    continue; // skip the rest of the checks for this token since we already know it is an error
                    }
                // next we check type
                String variableType = (checkType(currentScope, token.value));

                // now that we have the type of the variable, we can check it against the type
                // of the value being assigned to it, which is the next token after the assign
                // operator
                Token tokenValue = tokens.get(i + 2); // skip the assign operator

                // if the variable is a string, the value being assigned to it must be a string
                if ("string".equals(variableType) && tokenValue.tokenType != Lex.characterType.STRING) {
                    hasErrors = true;
                    errors++;
                    System.out.println("Error: Variable " + token.value
                            + " is of type String but is being assigned a non-string value at line " + token.line
                            + " position " + token.position);
                            continue; // skip the rest of the checks for this token since we already know it is an error
                }

                // if the variable is an int, the value being assigned to it must be an int
                else if ("int".equals(variableType) && tokenValue.tokenType != Lex.characterType.DIGIT) {
                    hasErrors = true;
                    errors++;
                    System.out.println("Error: Variable " + token.value
                            + " is of type Int but is being assigned a non-int value at line " + token.line
                            + " position " + token.position);
                            continue; // skip the rest of the checks for this token since we already know it is an error
                }

                // if the variable is a boolean, the value being assigned to it must be a
                // boolean
                else if ("boolean".equals(variableType) && tokenValue.tokenType != Lex.characterType.BOOLVAL) {
                    hasErrors = true;
                    errors++;
                    System.out.println("Error: Variable " + token.value
                            + " is of type Boolean but is being assigned a non-boolean value at line " + token.line
                            + " position " + token.position);
                            continue; // skip the rest of the checks for this token since we already know it is an error
                }
                if (!hasErrors) {
                    // if we have no errors, we can mark the variable as initialized
                    Symbol symbol = scopes.get(String.valueOf(currentScope)).get(token.value);
                    symbol.isInitialized = true;
                }

            }

            // print statements: check the expression inside print(Expr)
            else if (token.tokenType == Lex.characterType.PRINT) {
                // print is followed by ( Expr ), so the expression starts at i+2
                // skip past PRINT and LPAREN to get to the expression
                if (i + 2 < tokens.size()) {
                    Token exprToken = tokens.get(i + 2);
                    if (exprToken.tokenType == Lex.characterType.ID) {
                        boolean inScope = checkScope(activeScope, exprToken.value);
                        if (!inScope) {
                            hasErrors = true;
                            errors++;
                            System.out.println("Error: Variable " + exprToken.value
                                    + " is not in scope at line " + exprToken.line
                                    + " position " + exprToken.position);
                        } else {
                            // find the symbol following parent chain, check initialization, mark as used
                            int scope = activeScope;
                            while (scope >= 0) {
                                if (scopes.containsKey(String.valueOf(scope))
                                        && scopes.get(String.valueOf(scope)).containsKey(exprToken.value)) {
                                    Symbol symbol = scopes.get(String.valueOf(scope)).get(exprToken.value);
                                    if (!symbol.isInitialized) {
                                        hasErrors = true;
                                        errors++;
                                        System.out.println("Error: Variable " + exprToken.value
                                                + " used before initialization at line " + exprToken.line
                                                + " position " + exprToken.position);
                                        break; // exit the while loop — continue would restart it infinitely
                                    }
                                    symbol.isUsed = true;
                                    break;
                                }
                                scope = parentScope.getOrDefault(scope, -1);
                            }
                        }
                    }
                }
            }

            // while statements: check the boolean condition for scope and type
            else if (token.tokenType == Lex.characterType.WHILE) {
                checkBooleanCondition(i + 1, activeScope, "while");
            }

            // if statements: check the boolean condition for scope and type
            else if (token.tokenType == Lex.characterType.IF) {
                checkBooleanCondition(i + 1, activeScope, "if");
            }
        }

        // after processing all tokens, warn about unused variables
        checkUnusedVariables();
    }

    private void checkUnusedVariables() {
        for (String scopeKey : scopes.keySet()) {
            Hashtable<String, Symbol> scopeTable = scopes.get(scopeKey);
            for (String varName : scopeTable.keySet()) {
                Symbol sym = scopeTable.get(varName);
                if (!sym.isUsed) {
                    System.out.println("Warning: Variable " + varName
                            + " is declared in scope " + scopeKey + " but never used");
                }
            }
        }
    }

    // helper function to check the parent scopes for a variable, returns true if
    // the variable is in scope, false if it is not
    private boolean checkScope(int currentScope, String variableName) {
        int scope = currentScope;
        while (scope >= 0) {
            if (scopes.containsKey(String.valueOf(scope))
                    && scopes.get(String.valueOf(scope)).containsKey(variableName)) {
                return true;
            }
            scope = parentScope.getOrDefault(scope, -1);
        }
        return false;
    }

    // helper function to check the parent scopes for a variable, returns the type
    // of the variable if it is in scope, null if it is not
    private String checkType(int currentScope, String variableName) {
        int scope = currentScope;
        while (scope >= 0) {
            if (scopes.containsKey(String.valueOf(scope))
                    && scopes.get(String.valueOf(scope)).containsKey(variableName)) {
                return scopes.get(String.valueOf(scope)).get(variableName).type;
            }
            scope = parentScope.getOrDefault(scope, -1);
        }
        return null;
    }

    // validates the boolean condition that follows a while or if keyword
    // handles both simple boolval (true/false) and parenthesized (Expr BoolOp Expr)
    private void checkBooleanCondition(int startIndex, int currentScope, String statementKind) {

        Token first = tokens.get(startIndex);

        // simple case: while true { ... } or if false { ... }
        if (first.tokenType == Lex.characterType.BOOLVAL) {
            return;
        }

        // parenthesized case: ( Expr BoolOp Expr )
        if (first.tokenType == Lex.characterType.LPAREN) {
            int rparenIndex = findMatchingRParen(startIndex);
            if (rparenIndex == -1) {
                hasErrors = true;
                errors++;
                System.out.println("Error: Missing closing parenthesis in " + statementKind
                        + " condition at line " + first.line + " position " + first.position);
                return;
            }

            // find the boolop inside the parentheses
            int boolopIndex = -1;
            for (int j = startIndex + 1; j < rparenIndex; j++) {
                if (tokens.get(j).tokenType == Lex.characterType.BOOLOP) {
                    boolopIndex = j;
                    break;
                }
            }

            if (boolopIndex == -1) {
                hasErrors = true;
                errors++;
                System.out.println("Error: Missing boolean operator in " + statementKind
                        + " condition at line " + first.line + " position " + first.position);
                return;
            }

            // infer the types of the left and right sides
            String leftType = inferExprType(startIndex + 1, boolopIndex, currentScope);
            String rightType = inferExprType(boolopIndex + 1, rparenIndex, currentScope);

            if (leftType != null && rightType != null && !leftType.equals(rightType)) {
                hasErrors = true;
                errors++;
                System.out.println("Error: Type mismatch in " + statementKind
                        + " condition — left side is " + leftType + " but right side is " + rightType
                        + " at line " + first.line + " position " + first.position);
            }
        } else {
            hasErrors = true;
            errors++;
            System.out.println("Error: Invalid boolean condition for " + statementKind
                    + " statement at line " + first.line + " position " + first.position);
        }
    }

    // finds the index of the matching right parenthesis for a left parenthesis
    private int findMatchingRParen(int leftParenIndex) {
        int depth = 1;
        for (int i = leftParenIndex + 1; i < tokens.size(); i++) {
            if (tokens.get(i).tokenType == Lex.characterType.LPAREN) {
                depth++;
            } else if (tokens.get(i).tokenType == Lex.characterType.RPAREN) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // infers the type of an expression between startIndex (inclusive) and endIndex
    // (exclusive)
    private String inferExprType(int startIndex, int endIndex, int currentScope) {
        if (startIndex >= endIndex || startIndex >= tokens.size()) {
            return null;
        }

        Token first = tokens.get(startIndex);

        // string literal: starts with a quote
        if (first.tokenType == Lex.characterType.STRING) {
            return "string";
        }

        // integer literal
        if (first.tokenType == Lex.characterType.DIGIT) {
            return "int";
        }

        // boolean literal
        if (first.tokenType == Lex.characterType.BOOLVAL) {
            return "boolean";
        }

        // identifier: look up its declared type
        if (first.tokenType == Lex.characterType.ID) {
            boolean inScope = checkScope(currentScope, first.value);
            if (!inScope) {
                hasErrors = true;
                errors++;
                System.out.println("Error: Variable " + first.value + " is not in scope at line "
                        + first.line + " position " + first.position);
                return null;
            }
            // mark the variable as used, following the parent chain
            int scope = currentScope;
            while (scope >= 0) {
                if (scopes.containsKey(String.valueOf(scope))
                        && scopes.get(String.valueOf(scope)).containsKey(first.value)) {
                    Symbol sym = scopes.get(String.valueOf(scope)).get(first.value);
                    if (!sym.isInitialized) {
                        System.out.println("Warning: Variable " + first.value
                                + " used before initialization at line " + first.line
                                + " position " + first.position);
                    }
                    sym.isUsed = true;
                    return sym.type;
                }
                scope = parentScope.getOrDefault(scope, -1);
            }
        }

        return null;
    }
}
