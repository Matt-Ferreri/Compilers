import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits JVM {@code .class} files from the AST using ObjectWeb ASM.
 * Mirrors {@link CodeGen} / {@link LlvmCodeGen} structure (same node kinds, scope stack).
 */
public class JvmAsmCodeGen {

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "true", "false", "int", "string", "boolean", "if", "while", "print");

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z]+");

    private SemanticAnalyzer ctx;
    private MethodVisitor mv;
    private String internalClassName = "compile/Program1";
    private final List<String> errors = new ArrayList<>();
    private final ArrayList<Integer> scopeStack = new ArrayList<>();
    private int nextBlockScopeId;
    /** Maps {@code scopeId + "_" + name} to JVM local slot index. */
    private final Hashtable<String, Integer> varSlots = new Hashtable<>();
    private int nextLocal = 1; // slot 0 = main's String[] args
    private byte[] bytecode;

    public void run(Tree ast, SemanticAnalyzer semantic, int programIndex) {
        ctx = semantic;
        errors.clear();
        scopeStack.clear();
        nextBlockScopeId = 0;
        varSlots.clear();
        nextLocal = 1;
        bytecode = null;
        internalClassName = "compile/Program" + programIndex;
        if (ast == null || ast.getRoot() == null) {
            return;
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalClassName, null,
                "java/lang/Object", null);

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        mv.visitCode();

        genNode(ast.getRoot());

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        bytecode = cw.toByteArray();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public byte[] getBytecode() {
        return bytecode;
    }

    public void writeClassFile() throws IOException {
        if (bytecode == null) {
            return;
        }
        Path out = Path.of("out");
        Path pkg = out.resolve("compile");
        Files.createDirectories(pkg);
        String simple = internalClassName.substring(internalClassName.lastIndexOf('/') + 1);
        Files.write(pkg.resolve(simple + ".class"), bytecode);
    }

    private void reportError(String msg) {
        errors.add(msg);
    }

    private String slotKey(int scopeId, String name) {
        return scopeId + "_" + name;
    }

    private void declareVar(int scopeId, String name) {
        varSlots.put(slotKey(scopeId, name), nextLocal++);
    }

    private Integer lookupSlot(String name) {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            int sid = scopeStack.get(i);
            Integer sl = varSlots.get(slotKey(sid, name));
            if (sl != null) {
                return sl;
            }
        }
        reportError("unknown variable '" + name + "'");
        return null;
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
                int sid = n.blockScopeId >= 0 ? n.blockScopeId : nextBlockScopeId++;
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
        if (n.children.size() < 2) {
            return;
        }
        String name = n.children.get(1).name;
        int sid = scopeStack.get(scopeStack.size() - 1);
        declareVar(sid, name);
    }

    private void genAssignment(Tree.Node n) {
        String lhs = childIdentifier(n, 0);
        Tree.Node rhs = n.children.size() > 1 ? n.children.get(1) : null;
        Symbol dest = lookupSymbol(lhs);
        if (dest == null) {
            return;
        }
        Integer slot = lookupSlot(lhs);
        if (slot == null) {
            return;
        }
        if ("int".equals(dest.type)) {
            pushIntValue(rhs);
            mv.visitVarInsn(Opcodes.ISTORE, slot);
        } else if ("boolean".equals(dest.type)) {
            pushBoolAsInt(rhs);
            mv.visitVarInsn(Opcodes.ISTORE, slot);
        } else if ("string".equals(dest.type)) {
            String lit = stringExprLiteral(rhs);
            if (lit != null) {
                mv.visitLdcInsn(lit);
                mv.visitVarInsn(Opcodes.ASTORE, slot);
            } else {
                String other = findIdInSubtree(rhs);
                if (other != null) {
                    Integer srcSlot = lookupSlot(other);
                    if (srcSlot != null) {
                        mv.visitVarInsn(Opcodes.ALOAD, srcSlot);
                        mv.visitVarInsn(Opcodes.ASTORE, slot);
                    }
                }
            }
        }
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
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        if (sym != null) {
            Integer slot = lookupSlot(id);
            if (slot == null) {
                return;
            }
            if ("int".equals(sym.type)) {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                        "(I)V", false);
            } else if ("boolean".equals(sym.type)) {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                        "(Z)V", false);
            } else if ("string".equals(sym.type)) {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                        "(Ljava/lang/String;)V", false);
            }
            return;
        }
        if ("IntExpr".equals(arg.name)) {
            pushIntValue(arg);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                    "(I)V", false);
            return;
        }
        if ("StringExpr".equals(arg.name)) {
            String lit = stringExprLiteral(arg);
            if (lit != null) {
                mv.visitLdcInsn(lit);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                        "(Ljava/lang/String;)V", false);
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
        Label thenL = new Label();
        Label endL = new Label();
        emitCondBranch(cond, thenL, endL);
        mv.visitLabel(thenL);
        genNode(block);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(endL);
    }

    private void genWhile(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node block = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        Label head = new Label();
        Label end = new Label();
        if (Boolean.TRUE.equals(lit)) {
            mv.visitLabel(head);
            genNode(block);
            mv.visitJumpInsn(Opcodes.GOTO, head);
            return;
        }
        mv.visitLabel(head);
        Label body = new Label();
        emitCondBranch(cond, body, end);
        mv.visitLabel(body);
        genNode(block);
        mv.visitJumpInsn(Opcodes.GOTO, head);
        mv.visitLabel(end);
    }

    /**
     * Branch to {@code whenTrue} if {@code BooleanExpr} holds in source semantics; otherwise go to
     * {@code whenFalse}.
     */
    private void emitCondBranch(Tree.Node cond, Label whenTrue, Label whenFalse) {
        if (!"BooleanExpr".equals(cond.name)) {
            mv.visitJumpInsn(Opcodes.GOTO, whenFalse);
            return;
        }
        if (cond.children.size() == 1) {
            String v = cond.children.get(0).name;
            if ("true".equals(v)) {
                mv.visitJumpInsn(Opcodes.GOTO, whenTrue);
                return;
            }
            if ("false".equals(v)) {
                mv.visitJumpInsn(Opcodes.GOTO, whenFalse);
                return;
            }
        }
        int[] opIx = new int[] { -1 };
        String op = findBoolOp(cond, opIx);
        if (op == null || opIx[0] < 1) {
            mv.visitJumpInsn(Opcodes.GOTO, whenFalse);
            return;
        }
        Tree.Node lhs = cond.children.get(opIx[0] - 1);
        Tree.Node rhs = cond.children.get(opIx[0] + 1);
        pushIntValue(lhs);
        pushIntValue(rhs);
        if ("==".equals(op)) {
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, whenTrue);
        } else {
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, whenTrue);
        }
        mv.visitJumpInsn(Opcodes.GOTO, whenFalse);
    }

    private void pushIntValue(Tree.Node n) {
        if (n == null) {
            mv.visitInsn(Opcodes.ICONST_0);
            return;
        }
        if ("Expr".equals(n.name) && n.children.size() == 1) {
            pushIntValue(n.children.get(0));
            return;
        }
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            Boolean bl = booleanLiteral(n);
            if (bl != null) {
                mv.visitInsn(bl ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                return;
            }
        }
        if (isLeaf(n)) {
            Integer v = parseInt(n.name);
            if (v != null) {
                switch (v) {
                    case -1 -> mv.visitInsn(Opcodes.ICONST_M1);
                    case 0 -> mv.visitInsn(Opcodes.ICONST_0);
                    case 1 -> mv.visitInsn(Opcodes.ICONST_1);
                    case 2 -> mv.visitInsn(Opcodes.ICONST_2);
                    case 3 -> mv.visitInsn(Opcodes.ICONST_3);
                    case 4 -> mv.visitInsn(Opcodes.ICONST_4);
                    case 5 -> mv.visitInsn(Opcodes.ICONST_5);
                    default -> {
                        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                            mv.visitIntInsn(Opcodes.BIPUSH, v);
                        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                            mv.visitIntInsn(Opcodes.SIPUSH, v);
                        } else {
                            mv.visitLdcInsn(v);
                        }
                    }
                }
                return;
            }
            String id = leafAsId(n);
            if (id != null) {
                Symbol s = lookupSymbol(id);
                if (s != null && ("int".equals(s.type) || "boolean".equals(s.type))) {
                    Integer slot = lookupSlot(id);
                    if (slot != null) {
                        mv.visitVarInsn(Opcodes.ILOAD, slot);
                    }
                }
            }
            return;
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
                pushIntValue(n.children.get(plus - 1));
                pushIntValue(n.children.get(plus + 1));
                mv.visitInsn(Opcodes.IADD);
                return;
            }
            for (Tree.Node c : n.children) {
                pushIntValue(c);
                return;
            }
        }
        for (Tree.Node c : n.children) {
            pushIntValue(c);
            return;
        }
        mv.visitInsn(Opcodes.ICONST_0);
    }

    private void pushBoolAsInt(Tree.Node n) {
        Boolean b = booleanLiteral(n);
        if (b != null) {
            mv.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            return;
        }
        String id = findIdInSubtree(n);
        if (id != null) {
            Symbol s = lookupSymbol(id);
            if (s != null && "boolean".equals(s.type)) {
                Integer slot = lookupSlot(id);
                if (slot != null) {
                    mv.visitVarInsn(Opcodes.ILOAD, slot);
                }
            }
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
        }
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
