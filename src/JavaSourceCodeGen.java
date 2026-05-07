import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Emits readable Java source from the AST (package {@code compile}, class {@code ProgramN}).
 * Mirrors {@link JvmAsmCodeGen} structure for the same node kinds and scope stack.
 */
public class JavaSourceCodeGen {

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "true", "false", "int", "string", "boolean", "if", "while", "print");

    /** Java reserved words; append underscore if a source id collides (rare for this grammar). */
    private static final java.util.Set<String> JAVA_RESERVED = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null");

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z]+");

    /**
     * {@code while (true)} makes any following statements in the same method unconditionally
     * unreachable and fails {@code javac}. This condition is trivially always true at runtime but not
     * a boolean constant to the compiler.
     */
    private static final String JAVA_INFINITE_WHILE_COND =
            "(System.nanoTime() != 0L || System.nanoTime() == 0L)";

    private final List<String> errors = new ArrayList<>();
    private final ArrayList<Integer> scopeStack = new ArrayList<>();
    private int nextBlockScopeId;
    private final StringBuilder src = new StringBuilder();
    private int indentLevel;
    private int programIndex = 1;
    private String javaSource = "";

    /**
     * Model same-block shadowing ({@code int x} then {@code boolean x}): scopes map keeps only one
     * binding, but javac needs distinct locals and lookups must follow declaration order.
     */
    private static final class SrcBinding {
        final String javaId;
        final String miniType;

        SrcBinding(String javaId, String miniType) {
            this.javaId = javaId;
            this.miniType = miniType;
        }
    }

    private final HashMap<String, ArrayDeque<SrcBinding>> bindingsBySrcName = new HashMap<>();
    private final ArrayDeque<ArrayList<String>> blockDeclaredNamesStack = new ArrayDeque<>();
    private int nextBindingSerial;

    public void run(Tree ast, SemanticAnalyzer semantic, int programIndex) {
        java.util.Objects.requireNonNull(semantic);
        this.programIndex = programIndex;
        errors.clear();
        scopeStack.clear();
        nextBlockScopeId = 0;
        indentLevel = 0;
        src.setLength(0);
        javaSource = "";
        bindingsBySrcName.clear();
        blockDeclaredNamesStack.clear();
        nextBindingSerial = 0;
        if (ast == null || ast.getRoot() == null) {
            return;
        }

        line("package compile;");
        line("");
        line("public class Program" + programIndex + " {");
        indentLevel++;
        line("public static void main(String[] args) {");
        indentLevel++;

        genNode(ast.getRoot());

        indentLevel--;
        line("}");
        indentLevel--;
        line("}");

        javaSource = src.toString();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public String getJavaSource() {
        return javaSource;
    }

    public void writeJavaFile() throws IOException {
        if (javaSource.isEmpty()) {
            return;
        }
        Path pkg = Path.of("out", "java_src", "compile");
        Files.createDirectories(pkg);
        Path f = pkg.resolve("Program" + programIndex + ".java");
        Files.writeString(f, javaSource, StandardCharsets.UTF_8);
    }

    private void line(String s) {
        src.append("    ".repeat(Math.max(0, indentLevel)));
        src.append(s);
        src.append('\n');
    }

    private void reportError(String msg) {
        errors.add(msg);
    }

    private void genNode(Tree.Node n) {
        if (n == null) {
            return;
        }
        switch (n.name) {
            case "Program" -> {
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
            }
            case "Block" -> {
                boolean outer = scopeStack.isEmpty();
                int sid = n.blockScopeId >= 0 ? n.blockScopeId : nextBlockScopeId++;
                scopeStack.add(sid);
                blockDeclaredNamesStack.push(new ArrayList<>());
                if (!outer) {
                    line("{");
                    indentLevel++;
                }
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
                ArrayList<String> declaredHere = blockDeclaredNamesStack.pop();
                for (int i = declaredHere.size() - 1; i >= 0; i--) {
                    String nm = declaredHere.get(i);
                    ArrayDeque<SrcBinding> st = bindingsBySrcName.get(nm);
                    if (st != null && !st.isEmpty()) {
                        st.pop();
                    }
                }
                if (!outer) {
                    indentLevel--;
                    line("}");
                }
                scopeStack.remove(scopeStack.size() - 1);
            }
            case "VarDecl" -> genVarDecl(n);
            case "AssignmentStatement" -> genAssignment(n);
            case "PrintStatement" -> genPrint(n);
            case "IfStatement" -> genIf(n);
            case "WhileStatement" -> genWhile(n);
            default -> {
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
            }
        }
    }

    private void genVarDecl(Tree.Node n) {
        if (n.children.size() < 2) {
            return;
        }
        String srcType = n.children.get(0).name;
        String name = n.children.get(1).name;
        String jt = javaType(srcType);
        int sid = scopeStack.get(scopeStack.size() - 1);
        String jid = declareBinding(name, srcType, sid);
        line(jt + " " + jid + ";");
    }

    private static String javaType(String srcType) {
        if ("string".equals(srcType)) {
            return "String";
        }
        return srcType;
    }

    private void genAssignment(Tree.Node n) {
        String lhs = childIdentifier(n, 0);
        Tree.Node rhs = n.children.size() > 1 ? n.children.get(1) : null;
        SrcBinding dest = requireBinding(lhs);
        if (dest == null) {
            return;
        }
        if ("int".equals(dest.miniType)) {
            line(dest.javaId + " = " + intExprToString(rhs) + ";");
        } else if ("boolean".equals(dest.miniType)) {
            line(dest.javaId + " = " + booleanExprToString(rhs) + ";");
        } else if ("string".equals(dest.miniType)) {
            String lit = stringExprLiteral(rhs);
            if (lit != null) {
                line(dest.javaId + " = " + escapeJavaString(lit) + ";");
            } else {
                String other = findIdInSubtree(rhs);
                if (other != null) {
                    line(dest.javaId + " = " + javaRefCurrent(other) + ";");
                }
            }
        }
    }

    private void genPrint(Tree.Node n) {
        Tree.Node arg = pickPrintArg(n);
        if (arg == null) {
            return;
        }
        SrcBinding bound = null;
        String id = leafAsId(arg);
        if (id != null) {
            bound = peekBinding(id);
        }
        if (bound != null) {
            line("System.out.println(" + bound.javaId + ");");
            return;
        }
        if ("IntExpr".equals(arg.name)) {
            line("System.out.println(" + intExprToString(arg) + ");");
            return;
        }
        if ("StringExpr".equals(arg.name)) {
            String lit = stringExprLiteral(arg);
            if (lit != null) {
                line("System.out.println(" + escapeJavaString(lit) + ");");
            }
            return;
        }
        if ("BooleanExpr".equals(arg.name)) {
            Boolean lit = booleanLiteral(arg);
            if (lit != null) {
                line("System.out.println(" + lit + ");");
                return;
            }
            line("System.out.println(" + booleanExprToString(arg) + ");");
        }
    }

    private void genIf(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node block = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        if (Boolean.TRUE.equals(lit)) {
            genNode(block);
            return;
        }
        line("if (" + conditionToString(cond) + ") {");
        indentLevel++;
        genNode(block);
        indentLevel--;
        line("}");
    }

    private void genWhile(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node block = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        if (Boolean.TRUE.equals(lit)) {
            line("while (" + JAVA_INFINITE_WHILE_COND + ") {");
            indentLevel++;
            genNode(block);
            indentLevel--;
            line("}");
            return;
        }
        line("while (" + conditionToString(cond) + ") {");
        indentLevel++;
        genNode(block);
        indentLevel--;
        line("}");
    }

    /** Java expression usable in {@code if}/{@code while}, type-correct for primitives. */
    private String conditionToString(Tree.Node cond) {
        if (!"BooleanExpr".equals(cond.name)) {
            return "false";
        }
        if (cond.children.size() == 1) {
            String v = cond.children.get(0).name;
            if ("true".equals(v)) {
                return "true";
            }
            if ("false".equals(v)) {
                return "false";
            }
        }
        int[] opIx = new int[] { -1 };
        String op = findBoolOp(cond, opIx);
        if (op == null || opIx[0] < 1) {
            return "false";
        }
        Tree.Node lhs = cond.children.get(opIx[0] - 1);
        Tree.Node rhs = cond.children.get(opIx[0] + 1);
        String lt = operandKind(lhs);
        String rt = operandKind(rhs);
        if ("int".equals(lt) && "int".equals(rt)) {
            return intExprToString(lhs) + " " + op + " " + intExprToString(rhs);
        }
        if ("boolean".equals(lt) && "boolean".equals(rt)) {
            return booleanExprToString(lhs) + " " + op + " " + booleanExprToString(rhs);
        }
        return intPromoteExpr(lhs) + " " + op + " " + intPromoteExpr(rhs);
    }

    /** {@code int}-like comparison (matches JVM IF_ICMP): booleans as 0/1. */
    private String intPromoteExpr(Tree.Node n) {
        String k = operandKind(n);
        if ("int".equals(k)) {
            return intExprToString(n);
        }
        if ("boolean".equals(k)) {
            return "(" + booleanExprToString(n) + " ? 1 : 0)";
        }
        return "0";
    }

    private String operandKind(Tree.Node n) {
        if (n == null) {
            return "int";
        }
        Boolean bl = booleanLiteral(n);
        if (bl != null) {
            return "boolean";
        }
        if (intLiteralFromLeaf(n) != null) {
            return "int";
        }
        String id = findIdInExpr(n);
        if (id != null) {
            SrcBinding b = peekBinding(id);
            if (b != null) {
                if ("boolean".equals(b.miniType)) {
                    return "boolean";
                }
                if ("int".equals(b.miniType)) {
                    return "int";
                }
            }
        }
        if ("IntExpr".equals(n.name)) {
            return "int";
        }
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            return "boolean";
        }
        return "int";
    }

    private String declareBinding(String srcName, String miniType, int sid) {
        String jid = safeId(srcName) + "_s" + sid + "_d" + (nextBindingSerial++);
        bindingsBySrcName.computeIfAbsent(srcName, k -> new ArrayDeque<>()).push(new SrcBinding(jid, miniType));
        blockDeclaredNamesStack.peek().add(srcName);
        return jid;
    }

    private SrcBinding peekBinding(String srcName) {
        ArrayDeque<SrcBinding> d = bindingsBySrcName.get(srcName);
        return d == null || d.isEmpty() ? null : d.peek();
    }

    private SrcBinding requireBinding(String srcName) {
        SrcBinding b = peekBinding(srcName);
        if (b == null) {
            reportError("unknown variable '" + srcName + "'");
        }
        return b;
    }

    private String javaRefCurrent(String srcName) {
        SrcBinding b = peekBinding(srcName);
        return b != null ? b.javaId : safeId(srcName);
    }

    private String intExprToString(Tree.Node n) {
        if (n == null) {
            return "0";
        }
        if ("Expr".equals(n.name) && n.children.size() == 1) {
            return intExprToString(n.children.get(0));
        }
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            Boolean bl = booleanLiteral(n);
            if (bl != null) {
                return bl ? "1" : "0";
            }
        }
        if (isLeaf(n)) {
            Integer v = parseInt(n.name);
            if (v != null) {
                return String.valueOf(v);
            }
            String id = leafAsId(n);
            if (id != null) {
                SrcBinding b = peekBinding(id);
                if (b != null && "int".equals(b.miniType)) {
                    return b.javaId;
                }
                if (b != null && "boolean".equals(b.miniType)) {
                    return "(" + b.javaId + " ? 1 : 0)";
                }
            }
            return "0";
        }
        if ("IntExpr".equals(n.name)) {
            int plus = -1;
            for (int i = 0; i < n.children.size(); i++) {
                if ("+".equals(n.children.get(i).name)) {
                    plus = i;
                    break;
                }
            }
            if (plus > 0) {
                return "(" + intExprToString(n.children.get(plus - 1)) + " + "
                        + intExprToString(n.children.get(plus + 1)) + ")";
            }
            for (Tree.Node c : n.children) {
                return intExprToString(c);
            }
        }
        for (Tree.Node c : n.children) {
            return intExprToString(c);
        }
        return "0";
    }

    private String booleanExprToString(Tree.Node n) {
        Boolean litBool = booleanLiteral(n);
        if (litBool != null) {
            return String.valueOf(litBool);
        }
        String id = findIdInSubtree(n);
        if (id != null) {
            SrcBinding bound = peekBinding(id);
            if (bound != null && "boolean".equals(bound.miniType)) {
                return bound.javaId;
            }
        }
        return "false";
    }

    private static String safeId(String name) {
        if (JAVA_RESERVED.contains(name)) {
            return name + "_";
        }
        return name;
    }

    private static String escapeJavaString(String lit) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private Integer intLiteralFromLeaf(Tree.Node n) {
        return isLeaf(n) ? parseInt(n.name) : null;
    }

    private Tree.Node pickPrintArg(Tree.Node printStmt) {
        for (Tree.Node c : printStmt.children) {
            if ("Expr".equals(c.name) || "IntExpr".equals(c.name) || "StringExpr".equals(c.name)
                    || "BooleanExpr".equals(c.name)) {
                return unwrapExpr(c);
            }
            if (!c.children.isEmpty()) {
                Tree.Node inner = pickPrintArg(c);
                if (inner != null) {
                    return inner;
                }
            }
            if (c.children.isEmpty() && leafAsId(c) != null) {
                return c;
            }
        }
        return printStmt.children.isEmpty() ? null : printStmt.children.get(0);
    }

    private Tree.Node unwrapExpr(Tree.Node n) {
        if (!"Expr".equals(n.name)) {
            return n;
        }
        for (Tree.Node c : n.children) {
            if ("IntExpr".equals(c.name) || "StringExpr".equals(c.name) || "BooleanExpr".equals(c.name)) {
                return c;
            }
            if ("ID".equals(c.name)) {
                return c;
            }
        }
        return n.children.isEmpty() ? n : n.children.get(0);
    }

    private Boolean booleanConditionLiteral(Tree.Node cond) {
        if (!"BooleanExpr".equals(cond.name)) {
            return null;
        }
        if (cond.children.size() == 1) {
            String v = cond.children.get(0).name;
            if ("true".equals(v)) {
                return Boolean.TRUE;
            }
            if ("false".equals(v)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private Boolean booleanLiteral(Tree.Node n) {
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            String v = n.children.get(0).name;
            if ("true".equals(v)) {
                return true;
            }
            if ("false".equals(v)) {
                return false;
            }
        }
        return null;
    }

    private String findBoolOp(Tree.Node be, int[] opIdxOut) {
        for (int i = 0; i < be.children.size(); i++) {
            String nm = be.children.get(i).name;
            if ("==".equals(nm) || "!=".equals(nm)) {
                opIdxOut[0] = i;
                return nm;
            }
        }
        opIdxOut[0] = -1;
        return null;
    }

    private String stringExprLiteral(Tree.Node n) {
        if (!"StringExpr".equals(n.name)) {
            return null;
        }
        for (Tree.Node c : n.children) {
            if (isLeaf(c)) {
                return c.name;
            }
        }
        return null;
    }

    private Integer parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String leafAsId(Tree.Node n) {
        if (n == null || !isLeaf(n)) {
            return null;
        }
        String nm = n.name;
        if (nm != null && ID_PATTERN.matcher(nm).matches() && !KEYWORDS.contains(nm)) {
            return nm;
        }
        return null;
    }

    private String childIdentifier(Tree.Node n, int leafIndex) {
        int seen = 0;
        for (Tree.Node c : n.children) {
            String id = leafAsId(c);
            if (id != null) {
                if (seen == leafIndex) {
                    return id;
                }
                seen++;
            }
        }
        return null;
    }

    private String findIdInExpr(Tree.Node n) {
        if (n == null) {
            return null;
        }
        String id = leafAsId(n);
        if (id != null) {
            return id;
        }
        for (Tree.Node c : n.children) {
            String sub = findIdInExpr(c);
            if (sub != null) {
                return sub;
            }
        }
        return null;
    }

    private String findIdInSubtree(Tree.Node n) {
        if (n == null) {
            return null;
        }
        String id = leafAsId(n);
        if (id != null) {
            return id;
        }
        for (Tree.Node c : n.children) {
            String s = findIdInSubtree(c);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private boolean isLeaf(Tree.Node n) {
        return n.children == null || n.children.isEmpty();
    }
}
