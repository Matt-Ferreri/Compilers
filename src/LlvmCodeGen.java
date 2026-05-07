import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Emits LLVM IR (.ll) from the AST for use with {@code clang} / {@code lli}.
 * Mirrors the structure of {@link CodeGen} (6502) — same node kinds and scope stack.
 */
public class LlvmCodeGen {

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "true", "false", "int", "string", "boolean", "if", "while", "print");

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z]+");

    private SemanticAnalyzer ctx;
    private final List<String> errors = new ArrayList<>();
    private final StringBuilder allocas = new StringBuilder();
    private final StringBuilder instr = new StringBuilder();
    private final ArrayList<Integer> scopeStack = new ArrayList<>();
    private int nextBlockScopeId = 0;
    private int tmp = 0;
    private int lbl = 0;
    private final List<String> stringGlobals = new ArrayList<>();
    private int strIdx = 0;
    private String llvmModule = "";

    public void run(Tree ast, SemanticAnalyzer semantic) {
        ctx = semantic;
        errors.clear();
        allocas.setLength(0);
        instr.setLength(0);
        scopeStack.clear();
        nextBlockScopeId = 0;
        tmp = 0;
        lbl = 0;
        stringGlobals.clear();
        strIdx = 0;
        llvmModule = "";
        if (ast == null || ast.getRoot() == null) {
            return;
        }
        genNode(ast.getRoot());

        StringBuilder mod = new StringBuilder();
        mod.append("; Generated LLVM IR\n");
        mod.append("declare i32 @printf(i8*, ...)\n\n");
        mod.append("@.fmt_intnl = private unnamed_addr constant [4 x i8] c\"%d\\0A\\00\", align 1\n");
        mod.append("@.fmt_strnl = private unnamed_addr constant [4 x i8] c\"%s\\0A\\00\", align 1\n");
        for (String g : stringGlobals) {
            mod.append(g).append("\n");
        }
        mod.append("\ndefine i32 @main() {\nentry:\n");
        mod.append(allocas);
        mod.append(instr);
        mod.append("  ret i32 0\n}\n");
        llvmModule = mod.toString();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public String getLlvmIr() {
        return llvmModule;
    }

    public void writeIrFile(int programIndex) throws java.io.IOException {
        Path dir = Path.of("out");
        Files.createDirectories(dir);
        Path f = dir.resolve("program_" + programIndex + ".ll");
        Files.writeString(f, llvmModule, StandardCharsets.UTF_8);
    }

    private void reportError(String msg) {
        errors.add(msg);
    }

    private String freshReg() {
        return "%t" + (tmp++);
    }

    private String freshLabel(String p) {
        return p + (lbl++);
    }

    private String manglePtr(int scopeId, String name) {
        return "%ptr_s" + scopeId + "_" + name;
    }

    private Symbol lookupSymbol(String name) {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            int sid = scopeStack.get(i);
            Hashtable<String, Symbol> t = ctx.getScopes().get(String.valueOf(sid));
            if (t != null && t.containsKey(name)) {
                return t.get(name);
            }
        }
        reportError("unknown variable '" + name + "'");
        return null;
    }

    private int definingScopeId(String name) {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            int sid = scopeStack.get(i);
            Hashtable<String, Symbol> t = ctx.getScopes().get(String.valueOf(sid));
            if (t != null && t.containsKey(name)) {
                return sid;
            }
        }
        return -1;
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
                int sid = nextBlockScopeId++;
                scopeStack.add(sid);
                for (Tree.Node c : n.children) {
                    genNode(c);
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
        if (n.children == null || n.children.size() < 2) {
            return;
        }
        String type = n.children.get(0).name;
        String name = n.children.get(1).name;
        int sid = scopeStack.get(scopeStack.size() - 1);
        String full = manglePtr(sid, name);
        if ("int".equals(type) || "boolean".equals(type)) {
            allocas.append("  ").append(full).append(" = alloca i32, align 4\n");
        } else if ("string".equals(type)) {
            allocas.append("  ").append(full).append(" = alloca i8*, align 8\n");
        }
    }

    private void genAssignment(Tree.Node n) {
        String lhs = childIdentifier(n, 0);
        Tree.Node rhs = n.children.size() > 1 ? n.children.get(1) : null;
        Symbol dest = lookupSymbol(lhs);
        if (dest == null) {
            return;
        }
        int sid = definingScopeId(lhs);
        String ptr = manglePtr(sid, lhs);
        if ("int".equals(dest.type)) {
            String v = genIntExprValue(rhs);
            instr.append("  store i32 ").append(regOrImm(v)).append(", i32* ").append(ptr).append("\n");
        } else if ("boolean".equals(dest.type)) {
            String v = genBoolAsI32(rhs);
            instr.append("  store i32 ").append(regOrImm(v)).append(", i32* ").append(ptr).append("\n");
        } else if ("string".equals(dest.type)) {
            String lit = stringExprLiteral(rhs);
            if (lit != null) {
                String g = emitStringGlobal(lit);
                String r = freshReg();
                instr.append("  ").append(r).append(" = getelementptr inbounds [").append(lit.length() + 1)
                        .append(" x i8], [").append(lit.length() + 1).append(" x i8]* ")
                        .append(g).append(", i64 0, i64 0\n");
                instr.append("  store i8* ").append(r).append(", i8** ").append(ptr).append("\n");
            } else {
                String id = findIdInSubtree(rhs);
                if (id != null) {
                    int s2 = definingScopeId(id);
                    String sp = manglePtr(s2, id);
                    String r = freshReg();
                    instr.append("  ").append(r).append(" = load i8*, i8** ").append(sp).append(", align 8\n");
                    instr.append("  store i8* ").append(r).append(", i8** ").append(ptr).append("\n");
                }
            }
        }
    }

    private static String regOrImm(String v) {
        return v;
    }

    private String emitStringGlobal(String lit) {
        String name = "@.str_lit_" + (strIdx++);
        int n = lit.length() + 1;
        StringBuilder bytes = new StringBuilder("c\"");
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            if (c == '\\' || c == '"') {
                bytes.append('\\').append(c);
            } else if (c >= 32 && c < 127) {
                bytes.append(c);
            } else {
                bytes.append(String.format("\\%02X", (int) c));
            }
        }
        bytes.append("\\00\"");
        stringGlobals.add(name + " = private unnamed_addr constant [" + n + " x i8] " + bytes + ", align 1");
        return name;
    }

    private void genPrint(Tree.Node n) {
        Tree.Node arg = pickPrintArg(n);
        if (arg == null) {
            return;
        }
        Symbol sym = null;
        String id = leafAsId(arg);
        if (id != null) {
            sym = lookupSymbol(id);
        }
        if (sym != null) {
            if ("int".equals(sym.type) || "boolean".equals(sym.type)) {
                int sid = definingScopeId(id);
                String ptr = manglePtr(sid, id);
                String v = freshReg();
                instr.append("  ").append(v).append(" = load i32, i32* ").append(ptr).append(", align 4\n");
                String fmt = freshReg();
                instr.append("  ").append(fmt)
                        .append(" = getelementptr inbounds [4 x i8], [4 x i8]* @.fmt_intnl, i64 0, i64 0\n");
                instr.append("  call i32 (i8*, ...) @printf(i8* ").append(fmt).append(", i32 ").append(v)
                        .append(")\n");
            } else if ("string".equals(sym.type)) {
                int sid = definingScopeId(id);
                String ptr = manglePtr(sid, id);
                String v = freshReg();
                instr.append("  ").append(v).append(" = load i8*, i8** ").append(ptr).append(", align 8\n");
                String fmt = freshReg();
                instr.append("  ").append(fmt)
                        .append(" = getelementptr inbounds [4 x i8], [4 x i8]* @.fmt_strnl, i64 0, i64 0\n");
                instr.append("  call i32 (i8*, ...) @printf(i8* ").append(fmt).append(", i8* ").append(v)
                        .append(")\n");
            }
            return;
        }
        if ("IntExpr".equals(arg.name)) {
            String v = genIntExprValue(arg);
            String fmt = freshReg();
            instr.append("  ").append(fmt)
                    .append(" = getelementptr inbounds [4 x i8], [4 x i8]* @.fmt_intnl, i64 0, i64 0\n");
            instr.append("  call i32 (i8*, ...) @printf(i8* ").append(fmt).append(", i32 ").append(regOrImm(v))
                    .append(")\n");
            return;
        }
        if ("StringExpr".equals(arg.name)) {
            String lit = stringExprLiteral(arg);
            if (lit != null) {
                String g = emitStringGlobal(lit);
                String r = freshReg();
                instr.append("  ").append(r).append(" = getelementptr inbounds [").append(lit.length() + 1)
                        .append(" x i8], [").append(lit.length() + 1).append(" x i8]* ")
                        .append(g).append(", i64 0, i64 0\n");
                String fmt = freshReg();
                instr.append("  ").append(fmt)
                        .append(" = getelementptr inbounds [4 x i8], [4 x i8]* @.fmt_strnl, i64 0, i64 0\n");
                instr.append("  call i32 (i8*, ...) @printf(i8* ").append(fmt).append(", i8* ").append(r)
                        .append(")\n");
            }
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
        String thenL = freshLabel("then");
        String endL = freshLabel("endif");
        String creg = genThenConditionI1(cond);
        instr.append("  br i1 ").append(creg).append(", label %").append(thenL).append(", label %")
                .append(endL).append("\n");
        instr.append(thenL).append(":\n");
        genNode(block);
        instr.append("  br label %").append(endL).append("\n");
        instr.append(endL).append(":\n");
    }

    private void genWhile(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node block = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        String head = freshLabel("while_head");
        String bodyL = freshLabel("while_body");
        String endL = freshLabel("while_end");
        if (Boolean.TRUE.equals(lit)) {
            instr.append("  br label %").append(head).append("\n");
            instr.append(head).append(":\n");
            genNode(block);
            instr.append("  br label %").append(head).append("\n");
            return;
        }
        instr.append("  br label %").append(head).append("\n");
        instr.append(head).append(":\n");
        String creg = genThenConditionI1(cond);
        instr.append("  br i1 ").append(creg).append(", label %").append(bodyL).append(", label %")
                .append(endL).append("\n");
        instr.append(bodyL).append(":\n");
        genNode(block);
        instr.append("  br label %").append(head).append("\n");
        instr.append(endL).append(":\n");
    }

    /**
     * i1 value that is true exactly when the {@code if}/{@code while} <em>then</em> body should run
     * (same sense as {@link CodeGen} truth conditions).
     */
    private String genThenConditionI1(Tree.Node cond) {
        if (!"BooleanExpr".equals(cond.name)) {
            String r = freshReg();
            instr.append("  ").append(r).append(" = icmp ne i32 0, 0\n");
            return r;
        }
        if (cond.children.size() == 1) {
            String v = cond.children.get(0).name;
            if ("true".equals(v)) {
                String r = freshReg();
                instr.append("  ").append(r).append(" = icmp ne i32 1, 0\n");
                return r;
            }
            if ("false".equals(v)) {
                String r = freshReg();
                instr.append("  ").append(r).append(" = icmp ne i32 0, 0\n");
                return r;
            }
        }
        int[] opIx = new int[] { -1 };
        String op = findBoolOp(cond, opIx);
        if (op == null || opIx[0] < 1) {
            String r = freshReg();
            instr.append("  ").append(r).append(" = icmp ne i32 0, 0\n");
            return r;
        }
        Tree.Node lhs = cond.children.get(opIx[0] - 1);
        Tree.Node rhs = cond.children.get(opIx[0] + 1);
        String L = genValueAsI32(lhs);
        String R = genValueAsI32(rhs);
        String cmp = freshReg();
        if ("==".equals(op)) {
            instr.append("  ").append(cmp).append(" = icmp eq i32 ").append(regOrImm(L)).append(", ")
                    .append(regOrImm(R)).append("\n");
        } else {
            instr.append("  ").append(cmp).append(" = icmp ne i32 ").append(regOrImm(L)).append(", ")
                    .append(regOrImm(R)).append("\n");
        }
        return cmp;
    }

    /** Load variable or literal as i32 (ints and booleans as 0/1). */
    private String genValueAsI32(Tree.Node n) {
        if (n == null) {
            return "0";
        }
        Boolean bl = booleanLiteral(n);
        if (bl != null) {
            return bl ? "1" : "0";
        }
        Integer lit = intLiteralFromExpr(n);
        if (lit != null) {
            return String.valueOf(lit);
        }
        String id = findIdInExpr(n);
        if (id != null) {
            Symbol s = lookupSymbol(id);
            if (s != null && ("int".equals(s.type) || "boolean".equals(s.type))) {
                int sid = definingScopeId(id);
                String ptr = manglePtr(sid, id);
                String r = freshReg();
                instr.append("  ").append(r).append(" = load i32, i32* ").append(ptr).append(", align 4\n");
                return r;
            }
        }
        if ("IntExpr".equals(n.name)) {
            return genIntExprValue(n);
        }
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            return booleanLiteral(n) != null ? (booleanLiteral(n) ? "1" : "0") : "0";
        }
        return "0";
    }

    private String genBoolAsI32(Tree.Node n) {
        Boolean b = booleanLiteral(n);
        if (b != null) {
            return b ? "1" : "0";
        }
        String id = findIdInSubtree(n);
        if (id != null) {
            Symbol s = lookupSymbol(id);
            if (s != null && "boolean".equals(s.type)) {
                int sid = definingScopeId(id);
                String ptr = manglePtr(sid, id);
                String r = freshReg();
                instr.append("  ").append(r).append(" = load i32, i32* ").append(ptr).append(", align 4\n");
                return r;
            }
        }
        return "0";
    }

    private String genIntExprValue(Tree.Node n) {
        if (n == null) {
            return "0";
        }
        if ("Expr".equals(n.name) && n.children.size() == 1) {
            return genIntExprValue(n.children.get(0));
        }
        if (isLeaf(n)) {
            Integer v = parseInt(n.name);
            if (v != null) {
                return String.valueOf(v);
            }
            String id = leafAsId(n);
            if (id != null) {
                int sid = definingScopeId(id);
                String ptr = manglePtr(sid, id);
                String r = freshReg();
                instr.append("  ").append(r).append(" = load i32, i32* ").append(ptr).append(", align 4\n");
                return r;
            }
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
                String L = genIntExprValue(n.children.get(plus - 1));
                String R = genIntExprValue(n.children.get(plus + 1));
                String o = freshReg();
                instr.append("  ").append(o).append(" = add nsw i32 ").append(regOrImm(L)).append(", ")
                        .append(regOrImm(R)).append("\n");
                return o;
            }
            for (Tree.Node c : n.children) {
                return genIntExprValue(c);
            }
        }
        for (Tree.Node c : n.children) {
            return genIntExprValue(c);
        }
        return "0";
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

    private Integer intLiteralFromExpr(Tree.Node n) {
        if ("IntExpr".equals(n.name)) {
            for (Tree.Node c : n.children) {
                if (isLeaf(c)) {
                    Integer v = parseInt(c.name);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return isLeaf(n) ? parseInt(n.name) : null;
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
