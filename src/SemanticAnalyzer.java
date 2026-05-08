import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class SemanticAnalyzer {

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

    private final Deque<ConstPropFrame> constPropFrameStack = new ArrayDeque<>();
    private final Deque<Integer> constPropScopeStack = new ArrayDeque<>();
    private int nextConstPropScopeId = 0;

    /** Per-block state for constant propagation: known values, declarations, assignments. */
    private static final class ConstPropFrame {
        final Map<String, String> values = new HashMap<>();
        final Set<String> declaredHere = new HashSet<>();
        final Set<String> assignedHere = new HashSet<>();
    }

    /** Symbol tables per scope (string keys "0", "1", …) — used by CodeGen for address binding. */
    public Hashtable<String, Hashtable<String, Symbol>> getScopes() {
        return scopes;
    }

    public Hashtable<Integer, Integer> getParentScope() {
        return parentScope;
    }

    // if there is an error return true
    public boolean semanticErrors() {
        // if we have any errors, return true
        if (errors > 0) {
            return true;
        }
        return false;
    }

    public Tree run(List<Token> tokens, Tree cst) {
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
        foldConstantExpressions(AST.getRoot());
        checkScopeAndTypes(tokens);
        unrollSmallCountedWhileLoops(AST.getRoot());
        foldConstantExpressions(AST.getRoot());
        propagateConstants(AST.getRoot());
        foldConstantExpressions(AST.getRoot());
        labelAstBlockScopes(AST.getRoot(), tokens);
        eliminateUnreachableCode(AST.getRoot());
        foldConstantExpressions(AST.getRoot());
        return AST;
    }

    public void printAST() {
        System.out.println("\n--- Abstract Syntax Tree ---");
        System.out.println(AST.toString());
        System.out.println("---------------------------\n");
    }

    public Tree printAndReturnSymbolTable() {
        System.out.println("\n--- Symbol Table ---");
        for (String scopeKey : scopes.keySet()) {
            System.out.println("Scope " + scopeKey + ":");
            Hashtable<String, Symbol> scopeTable = scopes.get(scopeKey);
            List<String> names = new ArrayList<>(scopeTable.keySet());
            Collections.sort(names);
            for (String varName : names) {
                Symbol sym = scopeTable.get(varName);
                System.out.println("  " + varName + " | type: " + sym.type
                        + " | addr: " + sym.address
                        + " | initialized: " + sym.isInitialized
                        + " | used: " + sym.isUsed);
            }
        }
        System.out.println("--------------------");
        return symbolTable;
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
            case "IntExpr" -> reduceIntExpr(node);
            case "StringExpr" -> reduceStringExpr(node);
            case "BooleanExpr" -> reduceBooleanExpr(node);
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
            if ("BooleanExpr".equals(child.name)) {
                reduceBooleanExpr(child);
            } else if ("Block".equals(child.name)) {
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
            if ("BooleanExpr".equals(child.name)) {
                reduceBooleanExpr(child);
            } else if ("Block".equals(child.name)) {
                reduceBlock(child);
            }
        }
        AST.endChildren();
    }

    // if it is an expression, we need to go to the children and reduce them, dont
    // add to AST
    private void reduceExpr(Tree.Node node) {
        for (Tree.Node child : node.children) {
            if ("IntExpr".equals(child.name)) {
                reduceIntExpr(child);
            } else if ("StringExpr".equals(child.name)) {
                reduceStringExpr(child);
            } else if ("BooleanExpr".equals(child.name)) {
                reduceBooleanExpr(child);
            } else if ("ID".equals(child.name)) {
                AST.addNode(extractLeafValue(child), "leaf");
            }
        }
    }

    private void reduceIntExpr(Tree.Node node) {
        AST.addNode("IntExpr", "branch");
        for (Tree.Node child : node.children) {
            if ("Expr".equals(child.name)) {
                reduceExpr(child);
            } else if (isLeaf(child)) {
                AST.addNode(child.name, "leaf");
            }
        }
        AST.endChildren();
    }

    private void reduceStringExpr(Tree.Node node) {
        AST.addNode("StringExpr", "branch");
        StringBuilder stringValue = new StringBuilder();
        collectStringChars(node, stringValue);
        AST.addNode(stringValue.toString(), "leaf");
        AST.endChildren();
    }

    private void collectStringChars(Tree.Node node, StringBuilder builder) {
        for (Tree.Node child : node.children) {
            if ("CharList".equals(child.name)) {
                collectStringChars(child, builder);
            } else if (isLeaf(child) && !"\"".equals(child.name)) {
                builder.append(child.name);
            }
        }
    }

    private void reduceBooleanExpr(Tree.Node node) {
        AST.addNode("BooleanExpr", "branch");
        for (Tree.Node child : node.children) {
            if ("Expr".equals(child.name)) {
                reduceExpr(child);
            } else if (isLeaf(child) && !"(".equals(child.name) && !")".equals(child.name)) {
                AST.addNode(child.name, "leaf");
            }
        }
        AST.endChildren();
    }

    /** Fold compile-time constant int additions and boolean == / != into literals. */
    private void foldConstantExpressions(Tree.Node root) {
        if (root != null) {
            foldConstantExpressionsRecursive(root);
        }
    }

    private void foldConstantExpressionsRecursive(Tree.Node n) {
        if (n == null || n.children == null) {
            return;
        }
        for (Tree.Node c : new ArrayList<>(n.children)) {
            foldConstantExpressionsRecursive(c);
        }
        if ("IntExpr".equals(n.name)) {
            tryFoldIntExpr(n);
        } else if ("BooleanExpr".equals(n.name)) {
            tryFoldBooleanExpr(n);
        }
    }

    private void tryFoldIntExpr(Tree.Node n) {
        Integer v = evaluateIntExprConstant(n);
        if (v == null) {
            return;
        }
        if (n.children.size() == 1 && Integer.toString(v).equals(n.children.get(0).name)) {
            return;
        }
        n.children.clear();
        n.children.add(new Tree.Node(Integer.toString(v)));
    }

    /** Integer constant value of an IntExpr tree, or null if variables or incomplete. */
    private Integer evaluateIntExprConstant(Tree.Node n) {
        if (n == null) {
            return null;
        }
        if (!"IntExpr".equals(n.name)) {
            return parseIntConstant(n.name);
        }
        if (n.children == null || n.children.isEmpty()) {
            return parseIntConstant(n.name);
        }
        int plus = -1;
        for (int i = 0; i < n.children.size(); i++) {
            if ("+".equals(n.children.get(i).name)) {
                plus = i;
                break;
            }
        }
        if (plus < 0) {
            for (Tree.Node c : n.children) {
                if ("+".equals(c.name)) {
                    continue;
                }
                Integer v = evaluateIntExprConstant(c);
                if (v != null) {
                    return v;
                }
                v = parseIntConstant(c.name);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        if (plus == 0 || plus + 1 >= n.children.size()) {
            return null;
        }
        Tree.Node left = n.children.get(plus - 1);
        Tree.Node right = n.children.get(plus + 1);
        Integer L = evaluateIntExprConstant(left);
        if (L == null) {
            L = parseIntConstant(left.name);
        }
        Integer R = evaluateIntExprConstant(right);
        if (R == null) {
            R = parseIntConstant(right.name);
        }
        if (L == null || R == null) {
            return null;
        }
        return L + R;
    }

    private Integer parseIntConstant(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void tryFoldBooleanExpr(Tree.Node n) {
        if (n.children == null || n.children.size() < 3) {
            return;
        }
        int opIdx = -1;
        String op = null;
        for (int i = 0; i < n.children.size(); i++) {
            String nm = n.children.get(i).name;
            if ("==".equals(nm) || "!=".equals(nm)) {
                opIdx = i;
                op = nm;
                break;
            }
        }
        if (opIdx < 1 || opIdx + 1 >= n.children.size() || op == null) {
            return;
        }
        Tree.Node lhs = n.children.get(opIdx - 1);
        Tree.Node rhs = n.children.get(opIdx + 1);

        Boolean bL = evaluateBoolLiteral(lhs);
        Boolean bR = evaluateBoolLiteral(rhs);
        if (bL != null && bR != null) {
            boolean res = "==".equals(op) ? bL.equals(bR) : !bL.equals(bR);
            replaceBooleanWithLiteral(n, res);
            return;
        }

        Integer iL = evaluateIntOperand(lhs);
        Integer iR = evaluateIntOperand(rhs);
        if (iL != null && iR != null) {
            boolean res = "==".equals(op) ? iL.equals(iR) : !iL.equals(iR);
            replaceBooleanWithLiteral(n, res);
        }
    }

    private void replaceBooleanWithLiteral(Tree.Node n, boolean value) {
        n.children.clear();
        n.children.add(new Tree.Node(value ? "true" : "false"));
    }

    private Boolean evaluateBoolLiteral(Tree.Node n) {
        if (n == null) {
            return null;
        }
        if ("BooleanExpr".equals(n.name) && n.children != null && n.children.size() == 1) {
            String v = n.children.get(0).name;
            if ("true".equals(v)) {
                return Boolean.TRUE;
            }
            if ("false".equals(v)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** IntExpr constant or a numeric leaf; not an ID. */
    private Integer evaluateIntOperand(Tree.Node n) {
        if (n == null) {
            return null;
        }
        if ("IntExpr".equals(n.name)) {
            return evaluateIntExprConstant(n);
        }
        return parseIntConstant(n.name);
    }

    // --- constant propagation (after semantic checks, before final fold) ---

    private void propagateConstants(Tree.Node root) {
        if (root == null || root.children == null || root.children.isEmpty()) {
            return;
        }
        if (!"Program".equals(root.name)) {
            return;
        }
        constPropFrameStack.clear();
        constPropScopeStack.clear();
        nextConstPropScopeId = 0;
        Tree.Node block = root.children.get(0);
        if (block != null && "Block".equals(block.name)) {
            propagateBlock(block, true);
        }
    }

    private void enterConstPropBlock() {
        int sid = nextConstPropScopeId++;
        constPropScopeStack.push(sid);
        ConstPropFrame f = new ConstPropFrame();
        if (!constPropFrameStack.isEmpty()) {
            f.values.putAll(constPropFrameStack.peek().values);
        }
        constPropFrameStack.push(f);
    }

    private void exitConstPropBlock(boolean mergeOuterAssignments) {
        ConstPropFrame child = constPropFrameStack.pop();
        constPropScopeStack.pop();
        if (constPropFrameStack.isEmpty()) {
            return;
        }
        ConstPropFrame parent = constPropFrameStack.peek();
        for (String v : child.assignedHere) {
            if (child.declaredHere.contains(v)) {
                continue;
            }
            if (mergeOuterAssignments) {
                if (child.values.containsKey(v)) {
                    parent.values.put(v, child.values.get(v));
                } else {
                    parent.values.remove(v);
                }
            } else {
                parent.values.remove(v);
            }
        }
    }

    private void propagateBlock(Tree.Node block, boolean mergeOuterAssignments) {
        enterConstPropBlock();
        for (Tree.Node stmt : block.children) {
            propagateStatement(stmt);
        }
        exitConstPropBlock(mergeOuterAssignments);
    }

    private void propagateStatement(Tree.Node n) {
        if (n == null) {
            return;
        }
        switch (n.name) {
            case "VarDecl" -> propagateVarDecl(n);
            case "AssignmentStatement" -> propagateAssignmentStatement(n);
            case "PrintStatement" -> {
                rewriteIdsToConstantsInSubtree(n);
            }
            case "IfStatement" -> propagateIfStatement(n);
            case "WhileStatement" -> propagateWhileStatement(n);
            case "Block" -> propagateBlock(n, true);
            default -> {
                for (Tree.Node c : n.children) {
                    propagateStatement(c);
                }
            }
        }
    }

    private void propagateVarDecl(Tree.Node n) {
        ConstPropFrame f = constPropFrameStack.peek();
        if (n.children == null || n.children.size() < 2) {
            return;
        }
        String id = n.children.get(1).name;
        f.declaredHere.add(id);
        f.values.remove(id);
    }

    private void propagateAssignmentStatement(Tree.Node n) {
        ConstPropFrame f = constPropFrameStack.peek();
        if (n.children == null || n.children.size() < 2) {
            return;
        }
        String lhs = n.children.get(0).name;
        Tree.Node rhs = n.children.get(1);
        rewriteIdsToConstantsInSubtree(rhs);
        f.assignedHere.add(lhs);
        String constVal = extractCompileTimeConstantValue(rhs);
        if (constVal != null) {
            f.values.put(lhs, constVal);
        } else {
            f.values.remove(lhs);
        }
    }

    private void propagateIfStatement(Tree.Node n) {
        if (n.children == null || n.children.isEmpty()) {
            return;
        }
        rewriteIdsToConstantsInSubtree(n.children.get(0));
        if (n.children.size() >= 2 && "Block".equals(n.children.get(1).name)) {
            propagateBlock(n.children.get(1), false);
        }
    }

    private void propagateWhileStatement(Tree.Node n) {
        if (n.children == null || n.children.isEmpty()) {
            return;
        }
        // Do not substitute IDs into the condition if the body assigns them: the condition is
        // re-evaluated each iteration (unlike a single-shot if). Example: after "l = 0", rewriting
        // "0 == l" using l's constant 0 yields "0 == 0" even though l changes in the loop body.
        Set<String> skipIds = new HashSet<>();
        if (n.children.size() >= 2 && "Block".equals(n.children.get(1).name)) {
            collectVarsAssignedInLoopBody(n.children.get(1), skipIds);
        }
        rewriteIdsToConstantsInSubtree(n.children.get(0), skipIds);
        if (n.children.size() >= 2 && "Block".equals(n.children.get(1).name)) {
            propagateBlock(n.children.get(1), false);
        }
    }

    /** All variables assigned anywhere in a while body (including nested blocks / inner loops). */
    private void collectVarsAssignedInLoopBody(Tree.Node body, Set<String> out) {
        if (body == null) {
            return;
        }
        if ("Block".equals(body.name)) {
            if (body.children != null) {
                for (Tree.Node stmt : body.children) {
                    collectVarsAssignedInStatement(stmt, out);
                }
            }
            return;
        }
        collectVarsAssignedInStatement(body, out);
    }

    private void collectVarsAssignedInStatement(Tree.Node stmt, Set<String> out) {
        if (stmt == null) {
            return;
        }
        switch (stmt.name) {
            case "AssignmentStatement" -> {
                if (stmt.children != null && !stmt.children.isEmpty()) {
                    Tree.Node lhs = stmt.children.get(0);
                    if (lhs != null && lhs.name != null) {
                        out.add(lhs.name);
                    }
                }
            }
            case "IfStatement" -> {
                if (stmt.children != null) {
                    for (Tree.Node c : stmt.children) {
                        if (c != null && "Block".equals(c.name)) {
                            collectVarsAssignedInLoopBody(c, out);
                        }
                    }
                }
            }
            case "WhileStatement" -> {
                if (stmt.children != null && stmt.children.size() >= 2) {
                    Tree.Node inner = stmt.children.get(1);
                    if (inner != null && "Block".equals(inner.name)) {
                        collectVarsAssignedInLoopBody(inner, out);
                    }
                }
            }
            case "Block" -> collectVarsAssignedInLoopBody(stmt, out);
            default -> {
            }
        }
    }

    /** Post-order: replace reads of propagated constants with literal subtrees. */
    private void rewriteIdsToConstantsInSubtree(Tree.Node n) {
        rewriteIdsToConstantsInSubtree(n, null);
    }

    /**
     * Like {@link #rewriteIdsToConstantsInSubtree(Tree.Node)} but never replaces identifiers listed
     * in {@code skipIds} (non-null set).
     */
    private void rewriteIdsToConstantsInSubtree(Tree.Node n, Set<String> skipIds) {
        if (n == null) {
            return;
        }
        if (n.children != null) {
            for (Tree.Node c : new ArrayList<>(n.children)) {
                rewriteIdsToConstantsInSubtree(c, skipIds);
            }
        }
        if (!isLeaf(n)) {
            return;
        }
        if (skipIds != null && skipIds.contains(n.name)) {
            return;
        }
        ConstPropFrame f = constPropFrameStack.peek();
        if (f == null || !f.values.containsKey(n.name)) {
            return;
        }
        int scope = constPropScopeStack.peek();
        String typ = checkType(scope, n.name);
        if (typ == null) {
            return;
        }
        String val = f.values.get(n.name);
        Tree.Node rep = constantSubtreeForTypeAndValue(typ, val);
        if (rep != null) {
            replaceTreeNodeInParent(n, rep);
        }
    }

    private void replaceTreeNodeInParent(Tree.Node oldNode, Tree.Node newNode) {
        Tree.Node p = oldNode.parent;
        if (p == null || p.children == null) {
            return;
        }
        int i = p.children.indexOf(oldNode);
        if (i < 0) {
            return;
        }
        p.children.set(i, newNode);
        newNode.parent = p;
    }

    private Tree.Node constantSubtreeForTypeAndValue(String type, String value) {
        if (value == null) {
            return null;
        }
        if ("int".equals(type)) {
            if (parseIntConstant(value) == null) {
                return null;
            }
            Tree.Node wrap = new Tree.Node("IntExpr");
            wrap.children.add(new Tree.Node(value));
            return wrap;
        }
        if ("boolean".equals(type)) {
            if (!"true".equals(value) && !"false".equals(value)) {
                return null;
            }
            Tree.Node wrap = new Tree.Node("BooleanExpr");
            wrap.children.add(new Tree.Node(value));
            return wrap;
        }
        return null;
    }

    /**
     * String form stored in the propagation map: decimal int, or "true"/"false".
     */
    private String extractCompileTimeConstantValue(Tree.Node rhs) {
        if (rhs == null) {
            return null;
        }
        if ("IntExpr".equals(rhs.name)) {
            Integer v = evaluateIntExprConstant(rhs);
            return v == null ? null : Integer.toString(v);
        }
        if ("BooleanExpr".equals(rhs.name)) {
            Boolean b = evaluateBoolLiteral(rhs);
            if (b == null) {
                return null;
            }
            return b ? "true" : "false";
        }
        if (isLeaf(rhs)) {
            Integer i = parseIntConstant(rhs.name);
            if (i != null) {
                return Integer.toString(i);
            }
            if ("true".equals(rhs.name) || "false".equals(rhs.name)) {
                return rhs.name;
            }
        }
        return null;
    }

    // --- dead code elimination: remove unreachable if/while branches (constant conditions) ---

    /**
     * Removes {@code if(false)} / {@code while(false)} and hoists {@code if(true)} bodies into the
     * enclosing block. Run after constant folding so conditions are often literal.
     */
    private void eliminateUnreachableCode(Tree.Node root) {
        if (root == null) {
            return;
        }
        eliminateUnreachableInSubtree(root);
    }

    private void eliminateUnreachableInSubtree(Tree.Node n) {
        if (n == null) {
            return;
        }
        if ("Block".equals(n.name)) {
            List<Tree.Node> replacement = new ArrayList<>();
            for (Tree.Node child : new ArrayList<>(n.children)) {
                eliminateUnreachableInSubtree(child);
                replacement.addAll(transformStatementForDeadCodeElimination(child));
            }
            n.children.clear();
            for (Tree.Node x : replacement) {
                x.parent = n;
                n.children.add(x);
            }
            return;
        }
        if (n.children != null) {
            for (Tree.Node c : n.children) {
                eliminateUnreachableInSubtree(c);
            }
        }
    }

    /** Folded {@code true}/{@code false} condition, or {@code null} if not compile-time constant. */
    private Boolean foldedBooleanCondition(Tree.Node cond) {
        if (cond == null || !"BooleanExpr".equals(cond.name)) {
            return null;
        }
        return evaluateBoolLiteral(cond);
    }

    /**
     * Returns statements that replace {@code stmt} (empty if removed, many if {@code if(true)} hoisted).
     */
    private List<Tree.Node> transformStatementForDeadCodeElimination(Tree.Node stmt) {
        List<Tree.Node> one = new ArrayList<>(1);
        one.add(stmt);
        if (stmt == null) {
            return new ArrayList<>();
        }
        if ("IfStatement".equals(stmt.name)) {
            if (stmt.children == null || stmt.children.isEmpty()) {
                return one;
            }
            Tree.Node cond = stmt.children.get(0);
            Boolean lit = foldedBooleanCondition(cond);
            if (Boolean.FALSE.equals(lit)) {
                return new ArrayList<>();
            }
            if (Boolean.TRUE.equals(lit)) {
                if (stmt.children.size() < 2) {
                    return one;
                }
                Tree.Node body = stmt.children.get(1);
                if (!"Block".equals(body.name) || body.children == null) {
                    return one;
                }
                // Keep the then-Block so codegen scope nesting matches the symbol table.
                List<Tree.Node> hoisted = new ArrayList<>(1);
                hoisted.add(body);
                return hoisted;
            }
            return one;
        }
        if ("WhileStatement".equals(stmt.name)) {
            if (stmt.children == null || stmt.children.isEmpty()) {
                return one;
            }
            Tree.Node cond = stmt.children.get(0);
            Boolean lit = foldedBooleanCondition(cond);
            if (Boolean.FALSE.equals(lit)) {
                return new ArrayList<>();
            }
            return one;
        }
        return one;
    }

    // --- loop unrolling: counted while (i != N), N in 1..4, with i=0 before and i = 1 + i last in body ---

    private static final class CounterWhilePattern {
        final String varName;
        /** Trip count: {@code  while (var != N)} with var starting at 0 and +1 each iteration. */
        final int tripCount;

        CounterWhilePattern(String varName, int tripCount) {
            this.varName = varName;
            this.tripCount = tripCount;
        }
    }

    private void unrollSmallCountedWhileLoops(Tree.Node root) {
        if (root == null || !"Program".equals(root.name) || root.children == null || root.children.isEmpty()) {
            return;
        }
        Tree.Node block = root.children.get(0);
        if (block != null && "Block".equals(block.name)) {
            unrollWhileLoopsDfs(block);
        }
    }

    private void unrollWhileLoopsDfs(Tree.Node n) {
        if (n == null) {
            return;
        }
        if ("Block".equals(n.name)) {
            for (Tree.Node c : n.children) {
                if ("Block".equals(c.name)) {
                    unrollWhileLoopsDfs(c);
                } else if ("IfStatement".equals(c.name) && c.children != null && c.children.size() >= 2) {
                    unrollWhileLoopsDfs(c.children.get(1));
                } else if ("WhileStatement".equals(c.name) && c.children != null && c.children.size() >= 2) {
                    unrollWhileLoopsDfs(c.children.get(1));
                }
            }
            applyCountedWhileUnrollInBlock(n);
            return;
        }
        if (n.children != null) {
            for (Tree.Node c : n.children) {
                unrollWhileLoopsDfs(c);
            }
        }
    }

    private void applyCountedWhileUnrollInBlock(Tree.Node block) {
        if (block == null || block.children == null) {
            return;
        }
        List<Tree.Node> out = new ArrayList<>();
        boolean any = false;
        for (Tree.Node c : new ArrayList<>(block.children)) {
            if ("WhileStatement".equals(c.name)) {
                List<Tree.Node> u = tryUnrollCountedWhile(c, block);
                if (u != null) {
                    out.addAll(u);
                    any = true;
                } else {
                    out.add(c);
                }
            } else {
                out.add(c);
            }
        }
        if (!any) {
            return;
        }
        block.children.clear();
        for (Tree.Node x : out) {
            x.parent = block;
            block.children.add(x);
        }
    }

    private List<Tree.Node> tryUnrollCountedWhile(Tree.Node whileStmt, Tree.Node parentBlock) {
        if (whileStmt == null || whileStmt.children == null || whileStmt.children.size() < 2) {
            return null;
        }
        Tree.Node cond = whileStmt.children.get(0);
        if (foldedBooleanCondition(cond) != null) {
            return null;
        }
        CounterWhilePattern pat = parseNotEqualCounterWhilePattern(cond);
        if (pat == null) {
            return null;
        }
        if (!hasZeroInitBeforeWhile(parentBlock, whileStmt, pat.varName)) {
            return null;
        }
        Tree.Node body = whileStmt.children.get(1);
        if (!"Block".equals(body.name) || body.children == null || body.children.isEmpty()) {
            return null;
        }
        Tree.Node last = body.children.get(body.children.size() - 1);
        if (!isPlusOneIncrement(last, pat.varName)) {
            return null;
        }
        List<Tree.Node> template = new ArrayList<>();
        for (int i = 0; i < body.children.size() - 1; i++) {
            template.add(body.children.get(i));
        }
        List<Tree.Node> result = new ArrayList<>();
        for (int k = 0; k < pat.tripCount; k++) {
            for (Tree.Node tmpl : template) {
                Tree.Node copy = copyAstNode(tmpl);
                substituteIdentifierWithIntLiteral(copy, pat.varName, k);
                result.add(copy);
            }
        }
        result.add(makeIntAssignment(pat.varName, pat.tripCount));
        return result;
    }

    /** {@code var != N} or {@code N != var} with literal N in 1..4; trip count is N (var starts at 0, ++ each time). */
    private CounterWhilePattern parseNotEqualCounterWhilePattern(Tree.Node cond) {
        if (cond == null || !"BooleanExpr".equals(cond.name)) {
            return null;
        }
        int opIx = -1;
        for (int i = 0; i < cond.children.size(); i++) {
            if ("!=".equals(cond.children.get(i).name)) {
                opIx = i;
                break;
            }
        }
        if (opIx < 1 || opIx + 1 >= cond.children.size()) {
            return null;
        }
        Tree.Node lhs = cond.children.get(opIx - 1);
        Tree.Node rhs = cond.children.get(opIx + 1);
        String vLeft = identifierLeafName(lhs);
        Integer nRight = intConstFromExprSide(rhs);
        String vRight = identifierLeafName(rhs);
        Integer nLeft = intConstFromExprSide(lhs);
        if (vLeft != null && nRight != null) {
            return boundedTripPattern(vLeft, nRight);
        }
        if (vRight != null && nLeft != null) {
            return boundedTripPattern(vRight, nLeft);
        }
        return null;
    }

    private CounterWhilePattern boundedTripPattern(String var, int n) {
        if (n < 1 || n > 4) {
            return null;
        }
        return new CounterWhilePattern(var, n);
    }

    private String identifierLeafName(Tree.Node n) {
        if (!isLeaf(n)) {
            return null;
        }
        if (parseIntConstant(n.name) != null) {
            return null;
        }
        if ("true".equals(n.name) || "false".equals(n.name)) {
            return null;
        }
        return n.name;
    }

    private Integer intConstFromExprSide(Tree.Node n) {
        if (n == null) {
            return null;
        }
        if ("IntExpr".equals(n.name)) {
            return evaluateIntExprConstant(n);
        }
        return parseIntConstant(n.name);
    }

    private boolean hasZeroInitBeforeWhile(Tree.Node block, Tree.Node whileNode, String var) {
        int idx = block.children.indexOf(whileNode);
        if (idx <= 0) {
            return false;
        }
        for (int i = idx - 1; i >= 0; i--) {
            Tree.Node s = block.children.get(i);
            if (!"AssignmentStatement".equals(s.name) || s.children == null || s.children.isEmpty()) {
                continue;
            }
            if (!var.equals(s.children.get(0).name)) {
                continue;
            }
            Integer v = assignmentRhsIntValue(s);
            return v != null && v == 0;
        }
        return false;
    }

    private Integer assignmentRhsIntValue(Tree.Node assign) {
        if (assign == null || assign.children == null || assign.children.size() < 2) {
            return null;
        }
        Tree.Node rhs = assign.children.get(1);
        if ("IntExpr".equals(rhs.name)) {
            return evaluateIntExprConstant(rhs);
        }
        if (isLeaf(rhs)) {
            return parseIntConstant(rhs.name);
        }
        return null;
    }

    /** RHS is {@code 1 + var} as IntExpr leaves {@code [1][+][var]}. */
    private boolean isPlusOneIncrement(Tree.Node assign, String var) {
        if (!"AssignmentStatement".equals(assign.name) || assign.children == null || assign.children.size() < 2) {
            return false;
        }
        if (!var.equals(assign.children.get(0).name)) {
            return false;
        }
        Tree.Node rhs = assign.children.get(1);
        if (!"IntExpr".equals(rhs.name) || rhs.children == null || rhs.children.size() != 3) {
            return false;
        }
        if (!"1".equals(rhs.children.get(0).name) || !"+".equals(rhs.children.get(1).name)) {
            return false;
        }
        Tree.Node third = rhs.children.get(2);
        return isLeaf(third) && var.equals(third.name);
    }

    private Tree.Node copyAstNode(Tree.Node n) {
        Tree.Node c = new Tree.Node(n.name);
        c.blockScopeId = n.blockScopeId;
        if (n.children != null) {
            for (Tree.Node ch : n.children) {
                Tree.Node cc = copyAstNode(ch);
                c.children.add(cc);
                cc.parent = c;
            }
        }
        return c;
    }

    private void substituteIdentifierWithIntLiteral(Tree.Node n, String var, int k) {
        if (n == null) {
            return;
        }
        if (isLeaf(n) && var.equals(n.name)) {
            Tree.Node rep = new Tree.Node("IntExpr");
            Tree.Node lit = new Tree.Node(Integer.toString(k));
            rep.children.add(lit);
            lit.parent = rep;
            replaceTreeNodeInParent(n, rep);
            return;
        }
        if (n.children != null) {
            for (Tree.Node c : new ArrayList<>(n.children)) {
                substituteIdentifierWithIntLiteral(c, var, k);
            }
        }
    }

    private Tree.Node makeIntAssignment(String var, int value) {
        Tree.Node asg = new Tree.Node("AssignmentStatement");
        Tree.Node lhs = new Tree.Node(var);
        Tree.Node rhs = new Tree.Node("IntExpr");
        rhs.children.add(new Tree.Node(Integer.toString(value)));
        rhs.children.get(0).parent = rhs;
        asg.children.add(lhs);
        asg.children.add(rhs);
        lhs.parent = asg;
        rhs.parent = asg;
        return asg;
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
                    Symbol symbol = resolveSymbol(currentScope, token.value);
                    if (symbol != null) {
                        symbol.isInitialized = true;
                    }
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

    /** Innermost scope that defines {@code variableName}, or {@code null}. */
    private Symbol resolveSymbol(int currentScope, String variableName) {
        int scope = currentScope;
        while (scope >= 0) {
            if (scopes.containsKey(String.valueOf(scope))
                    && scopes.get(String.valueOf(scope)).containsKey(variableName)) {
                return scopes.get(String.valueOf(scope)).get(variableName);
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

            // ( true ) / ( false ) — single BoolVal, no BOOLOP
            if (rparenIndex == startIndex + 2
                    && tokens.get(startIndex + 1).tokenType == Lex.characterType.BOOLVAL) {
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

    /**
     * Opening scope id for each {@code LBRACE} in source order (matches {@link #checkScopeAndTypes}).
     */
    private static ArrayDeque<Integer> collectBraceScopeIdsInOrder(List<Token> tokens) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int activeScope = -1;
        int currentScope = 0;
        Hashtable<Integer, Integer> localParent = new Hashtable<>();
        for (Token token : tokens) {
            if (token.tokenType == Lex.characterType.LBRACE) {
                int opening = currentScope;
                localParent.put(opening, activeScope);
                activeScope = opening;
                q.addLast(opening);
                currentScope++;
            } else if (token.tokenType == Lex.characterType.RBRACE) {
                activeScope = localParent.getOrDefault(activeScope, -1);
            }
        }
        return q;
    }

    /** DFS preorder assigns each AST {@code Block} the next id from {@link #collectBraceScopeIdsInOrder}. */
    private static void labelAstBlockScopes(Tree.Node n, List<Token> tokens) {
        if (n == null) {
            return;
        }
        ArrayDeque<Integer> deque = collectBraceScopeIdsInOrder(tokens);
        labelAstBlockScopesDfs(n, deque);
    }

    private static void labelAstBlockScopesDfs(Tree.Node n, ArrayDeque<Integer> deque) {
        if (n == null) {
            return;
        }
        if ("Block".equals(n.name) && !deque.isEmpty()) {
            n.blockScopeId = deque.removeFirst();
        }
        for (Tree.Node c : n.children) {
            labelAstBlockScopesDfs(c, deque);
        }
    }
}
