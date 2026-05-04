import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * Memory starts with code, when code finishes stack, heap works from bottom up
 * Code grows from 0 while the AST is walked.
 * Stack starts at the first byte after the last opcode (known only when codegen finishes).
 * Heap for string literals is packed downward from the end of memory (ASCII + 0x00 each).
 * Stack and heap meet in the middle; overlap is an error.
 *
 */
public class CodeGen {

    public static final int MEM_SIZE = 256;

    // opcode constants for the 6502a assembly language
    private static final int LDA_IMM = 0xA9;
    private static final int LDA_ABS = 0xAD;
    private static final int STA_ABS = 0x8D;
    private static final int ADC_ABS = 0x6D;
    private static final int LDX_IMM = 0xA2;
    private static final int LDX_ABS = 0xAE;
    private static final int LDY_IMM = 0xA0;
    private static final int LDY_ABS = 0xAC;
    private static final int CPX_ABS = 0xEC;
    private static final int BNE = 0xD0;
    private static final int BRK = 0x00;
    private static final int SYS = 0xFF;

    // keywords for the 6502a assembly language
    // when parsing the AST, if the node is a keyword, it will be replaced with the corresponding opcode
    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "true", "false", "int", "string", "boolean", "if", "while", "print");

    // full length of the memory
    private final byte[] memory = new byte[MEM_SIZE];
    // semantic analyzer for scopes
    private SemanticAnalyzer ctx;
    // verbose mode for the code generator
    private boolean verbose;
    // pointer to where the next byte will be written
    private int codePtr;
    // lowest address used by heap; heap occupies [heapBottom .. MEM_SIZE-1]
    private int heapBottom;

    // filled in after the code is ran, record length of code, stack, and heap for reporting
    private int layoutCodeLen;
    private int layoutStackBase;
    private int layoutHeapBottom;

    // list of errors that occurred during the code generation
    private final List<String> errors = new ArrayList<>();
    // list of scopes that are currently open
    private final ArrayList<Integer> scopeStack = new ArrayList<>();
    // id of the next block scope, to keep track of the scope id for the next block
    private int nextBlockScopeId;

    // list of relative branch patches to be applied after the code is generated
    private final List<RelPatch> relPatches = new ArrayList<>();
    // list of absolute symbol patches to be applied after the code is generated
    private final List<AbsSymPatch> absSymPatches = new ArrayList<>();
    // list of absolute scratch operand offsets to be applied after the code is generated
    private final List<Integer> absScratchOperandOffsets = new ArrayList<>();
    // map of string literals to their heap addresses
    private final Map<String, Integer> stringToHeap = new LinkedHashMap<>();


    // next classes are used to store the values that aren't known yet during code generation

    // when using BNE store the operand offset and the target PC
    private static final class RelPatch {
        final int operandOffset;
        final int targetPc;

        RelPatch(int operandOffset, int targetPc) {
            this.operandOffset = operandOffset;
            this.targetPc = targetPc;
        }
    }

    // referneces a variable that comes after code generation 
    private static final class AbsSymPatch {
        final int operandOffset;
        final Symbol sym;

        AbsSymPatch(int operandOffset, Symbol sym) {
            this.operandOffset = operandOffset;
            this.sym = sym;
        }
    }

    public void run(Tree ast, SemanticAnalyzer semantic, boolean verbose) {
        this.ctx = semantic; // creating new semantic analyzer
        this.verbose = verbose; // setting verbose mode
        Arrays.fill(memory, (byte) 0); // filling the memory with 0s
        errors.clear(); // clearing the list of errors
        codePtr = 0; // setting the code pointer to 0
        heapBottom = MEM_SIZE; // setting the heap bottom to the end of memory
        absSymPatches.clear(); // clearing the list of absolute symbol patches
        absScratchOperandOffsets.clear(); // clearing the list of absolute scratch operand offsets
        stringToHeap.clear(); // clearing the map of string literals to their heap addresses
        relPatches.clear(); // clearing the list of relative branch patches
        scopeStack.clear(); // clearing the list of scopes
        nextBlockScopeId = 0; // setting the next block scope id to 0
        resetSymbolAddresses(); // resetting the symbol addresses

        // while the AST is not null and the root is not null, generate the code
        if (ast != null && ast.getRoot() != null) {
            genNode(ast.getRoot()); // ge the node from the AST
        }
        emitOp("BRK", BRK); // emit the BRK opcode at the end of the code

        applyRelativePatches(); // apply the relative branch patches
        finalizeStackHeapLayout(); // finalize the stack and heap layout

        // if verbose mode is on, print the code generation
        if (verbose) {
            System.out.println("CODEGEN: code bytes [0.." + (layoutCodeLen - 1) + "], stack [$"
                    + String.format("%02X", layoutStackBase) + "..), heap [$"
                    + String.format("%02X", layoutHeapBottom) + "..FF]");
        }
    }

    // reset the addresses of the symbols to -1 to indicate that the address is not set yet
    // finalizeStackHeapLayout will then set the addresses of the symbols
    private void resetSymbolAddresses() {
        Hashtable<String, Hashtable<String, Symbol>> scopes = ctx.getScopes();
        for (String sk : scopes.keySet()) {
            Hashtable<String, Symbol> t = scopes.get(sk);
            for (Symbol symbol : t.values()) {
                // temporarily set the address to -1 to indicate that the address is not set yet
                symbol.address = -1;
            }   
        }
    }

    // if there are any errors, return true, used in the main method to check if the code generation had errors
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    // returns the memory image as a byte array
    public byte[] getMemoryImage() {
        return Arrays.copyOf(memory, MEM_SIZE);
    }

    // returns the code bytes as a byte array
    public byte[] getCodeBytes() {
        return getMemoryImage();
    }

    // builds a list of symbols in the order of the stack layout
    // finalizeStackHeapLayout will then set the addresses of the symbols using this list
    private List<Symbol> symbolsInLayoutOrder() {
        Hashtable<String, Hashtable<String, Symbol>> scopes = ctx.getScopes();
        List<String> scopeKeys = new ArrayList<>(scopes.keySet());
        scopeKeys.sort(Comparator.comparingInt(Integer::parseInt));
        List<Symbol> out = new ArrayList<>();
        for (String sk : scopeKeys) {
            Hashtable<String, Symbol> table = scopes.get(sk);
            List<String> names = new ArrayList<>(table.keySet());
            Collections.sort(names);
            for (String name : names) {
                out.add(table.get(name));
            }
        }
        return out;
    }

    // finalizes the stack and heap layout by setting the addresses of the symbols
    // each symbol is assigned an address in the order of the stack layout 
    // assigns Symbol.address to stack base + the index of the symbol in the list
    private void finalizeStackHeapLayout() {
        layoutStackBase = codePtr;
        layoutCodeLen = layoutStackBase;

        List<Symbol> order = symbolsInLayoutOrder();
        int stackBase = layoutStackBase;
        for (int i = 0; i < order.size(); i++) {
            order.get(i).address = stackBase + i;
        }
        int scratchSlots = absScratchOperandOffsets.isEmpty() ? 0 : 1;
        int scratchAddr = stackBase + order.size();
        for (AbsSymPatch p : absSymPatches) {
            int a = p.sym.address;
            if (a < 0) {
                reportError("internal: symbol address not set");
                continue;
            }
            memWriteLe(p.operandOffset, a);
        }
        if (scratchSlots == 1) {
            for (int off : absScratchOperandOffsets) {
                memWriteLe(off, scratchAddr);
            }
        }
        int stackEnd = stackBase + order.size() + scratchSlots;
        layoutHeapBottom = heapBottom;
        if (stackEnd > heapBottom) {
            reportError("stack/heap overlap: stack needs up to $" + String.format("%02X", stackEnd - 1)
                    + " heap starts at $" + String.format("%02X", heapBottom));
        }
    }

    // writes a 16-bit address to the memory at the given offset
    // the address is written as two bytes, the least significant byte first (Little Endian) and the most significant byte second
    private void memWriteLe(int offset, int address16) {
        memory[offset] = (byte) lo(address16);
        memory[offset + 1] = (byte) hi(address16);
    }

    // reports an error and adds it to the list of errors and prints it to the console
    private void reportError(String msg) {
        errors.add(msg);
        System.out.println("CodeGen error: " + msg);
    }

    // returns the current program counter
    private int pc() {
        return codePtr;
    }

    // emits an opcode and the bytes to the memory
    private void emitOp(String mnemonic, int... bytes) {
        // if the heap is full, report an error
        if (codePtr + bytes.length > MEM_SIZE) {
            reportError("code overflow past end of memory");
            return;
        }
        // if the code is going to run into the heap, report an error
        if (codePtr + bytes.length > heapBottom) {
            reportError("code ran into heap");
            return;
        }
        // if verbose mode is on, print the opcode and the bytes in hex
        if (verbose) {
            StringBuilder line = new StringBuilder("  ");
            line.append(mnemonic).append('\t');
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(String.format("%02X", bytes[i] & 0xFF));
            }
            System.out.println(line);
        }
        for (int b : bytes) {
            memory[codePtr++] = (byte) (b & 0xFF);
        }
    }

    /** Absolute addressing to a variable — operand patched after stack placement. */
    private void emitAbsSym(String mnemonic, int opcode, Symbol sym) {
        emitOp(mnemonic + " [sym]", opcode, 0, 0);
        absSymPatches.add(new AbsSymPatch(codePtr - 2, sym));
    }

    /** Absolute addressing for codegen scratch (one byte after stack vars). */
    private void emitAbsScratch(String mnemonic, int opcode) {
        emitOp(mnemonic + " [scratch]", opcode, 0, 0);
        absScratchOperandOffsets.add(codePtr - 2);
    }

    //the next two functions are used to get the least and most significant bytes of an integer for little endian encoding

    // returns the least significant byte of the given integer
    private static int lo(int a) {
        return a & 0xFF;
    }

    // returns the most significant byte of the given integer
    private static int hi(int a) {
        return (a >> 8) & 0xFF;
    }

    // Heap grows down from 255 towards 0; returns start index of NUL-terminated string.
    // used to allocate a string to the heap and return the start index of the string 
    private int heapAllocString(String s) {
        Integer existing = stringToHeap.get(s);
        if (existing != null) {
            return existing;
        }
        byte[] raw = s.getBytes(StandardCharsets.US_ASCII);
        int n = raw.length + 1;
        heapBottom -= n;
        if (heapBottom < 0) {
            reportError("heap overflow");
            heapBottom += n;
            return MEM_SIZE - 1;
        }
        if (heapBottom < codePtr) {
            reportError("heap collided with code while allocating string");
            heapBottom += n;
            return MEM_SIZE - 1;
        }
        for (int i = 0; i < raw.length; i++) {
            memory[heapBottom + i] = raw[i];
        }
        memory[heapBottom + raw.length] = 0;
        stringToHeap.put(s, heapBottom);
        return heapBottom;
    }

    // looks up a symbol in the current scope
    // returns the symbol if found, otherwise returns null and reports an error if the symbol is not found
    // main job is to find the symbol, the error reporting is secondary and acts as a safety net
    private Symbol lookup(String name) {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            int sid = scopeStack.get(i);
            Hashtable<String, Symbol> t = ctx.getScopes().get(String.valueOf(sid));
            if (t != null && t.containsKey(name)) {
                return t.get(name);
            }
        }
        // should not happen if semantic analysis is working correctly, but if it does, fail gracefully and report
        reportError("unknown variable '" + name + "'");
        return null;
    }

    // main method for code gen, recursively generates the code for the AST
    private void genNode(Tree.Node n) {
        // if the node is null, return (fail gracefully)
        if (n == null) {
            return;
        }
        switch (n.name) {
            // programs do not generate code, they just go to their children
            case "Program" -> {
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
            }
            // blocks generate for code their children and add a scope ot the stack
            case "Block" -> {
                int sid = nextBlockScopeId++;
                scopeStack.add(sid);
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
                scopeStack.remove(scopeStack.size() - 1);
            }
            // variable declarations do not generate code, they just add a symbol to the stack
            case "VarDecl" -> { }
            // assignment statements generate code to assign the value of the right hand side to the left hand side
            case "AssignmentStatement" -> genAssignment(n);
            // print statements generate code to print the value of the expression
            case "PrintStatement" -> genPrint(n);
            // if statements generate code to execute the then body if the condition is true
            case "IfStatement" -> genIf(n);
            // while statements generate code to execute the body of the loop while the condition is true
            case "WhileStatement" -> genWhile(n);
            // default case for other nodes, just generate code for their children
            default -> {
                for (Tree.Node c : n.children) {
                    genNode(c);
                }
            }
        }
    }

    // assignment statements generate code to assign the value of the right hand side to the left hand side
    private void genAssignment(Tree.Node n) {
        // name of the left hand side of the assignment, the variable
        String lhs = childIdentifier(n, 0);
        // look up the symbol for the variable
        Symbol dest = lookup(lhs);

        // right hand side of the assignment, the value being assigned to the variable
        Tree.Node rhs = n.children.size() > 1 ? n.children.get(1) : null;

        // if the variable is an int, generate code to assign the value of the right hand side to the left hand side
        if ("int".equals(dest.type)) {
            // generate code to assign the value of the right hand side to the left hand side
            genIntExprValueInA(rhs);
            // emit the opcode to store the value in the variable
            emitAbsSym("STA abs", STA_ABS, dest);
        } else if ("string".equals(dest.type)) {
            // if the variable is a string, generate code to assign the value of the right hand side to the left hand side
            String lit = stringExprLiteral(rhs);
            if (lit != null) {
                // allocate a string to the heap and get the pointer to it
                int ptr = heapAllocString(lit);
                // emit the opcode to load the pointer into the accumulator
                emitOp("LDA #heapPtr", LDA_IMM, ptr & 0xFF);
                emitAbsSym("STA abs (string ref)", STA_ABS, dest);
            } else {
                // if the right hand side is not a string literal, find the ID of the variable in the subtree
                String other = findIdInSubtree(rhs);
                if (other != null) {
                    // look up the symbol for the variable
                    Symbol src = lookup(other);
                    // if the variable is found, generate code to assign the value of the right hand side to the left hand side
                    if (src != null) {
                        emitAbsSym("LDA abs (copy ref)", LDA_ABS, src);
                        // emit the opcode to store the value in the variable
                        emitAbsSym("STA abs", STA_ABS, dest);
                    }
                }
            }
        // if the variable is a boolean, generate code to assign the value of the right hand side to the left hand side
        } else if ("boolean".equals(dest.type)) {
            Boolean b = booleanLiteral(rhs);
            // emit the opcode to load the value into the accumulator
            emitOp("LDA #bool", LDA_IMM, b ? 1 : 0);
            // emit the opcode to store the value in the variable
            emitAbsSym("STA abs", STA_ABS, dest);
        }
    }

    // print statements generate code to print the value of the expression
    private void genPrint(Tree.Node n) {
        // pick the argument to print, the expression to print
        Tree.Node arg = pickPrintArg(n);
        // if the argument is null, report an error
        Symbol sym = null;
        String id = leafAsId(arg);
        if (id != null) {
            sym = lookup(id);
        }
        // if the symbol is not null, generate code to print the value of the expression
        if (sym != null) {
            if ("int".equals(sym.type) || "boolean".equals(sym.type)) {
                emitAbsSym("LDY abs", LDY_ABS, sym);
                emitOp("LDX #01 SYS", LDX_IMM, 0x01, SYS);
            } else if ("string".equals(sym.type)) {
                emitAbsSym("LDY abs (ref)", LDY_ABS, sym);
                emitOp("LDX #02 SYS", LDX_IMM, 0x02, SYS);
            }
            return;
        }
        // if the argument is an int expression, generate code to print the value of the expression
        if ("IntExpr".equals(arg.name)) {
            genIntExprValueInA(arg);
            // no TAY in ISA: stage A in scratch, then LDY abs (course table)
            emitAbsScratch("STA scratch", STA_ABS);
            emitAbsScratch("LDY abs (from scratch)", LDY_ABS);
            emitOp("LDX #01 SYS", LDX_IMM, 0x01, SYS);
            return;
        }
        // if the argument is a string expression, generate code to print the value of the expression
        if ("StringExpr".equals(arg.name)) {
            String lit = stringExprLiteral(arg);
            if (lit != null) {
                int ptr = heapAllocString(lit);
                emitOp("LDY #heapPtr", LDY_IMM, ptr & 0xFF);
                emitOp("LDX #02 SYS", LDX_IMM, 0x02, SYS);
                return;
            }
        }
    }

    // picks the argument to print, the expression to print
    // returns the expression to print
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

    // unwraps the expression to print
    // returns the expression to print
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

    /**
     * If-statement codegen.
     * <ul>
     *   <li>{@code if (false)} — no code (body never runs).</li>
     *   <li>{@code if (true)} — body only (no test).</li>
     *   <li>Otherwise we emit {@code prepareCpxLeftRight} (compare + {@code CPX}), then
     *       {@code genThenAfterCpx}: condition <strong>true</strong> runs the block; condition
     *       <strong>false</strong> branches to the first instruction <em>after</em> the whole if
     *       (past the block).</li>
     * </ul>
     */
    private void genIf(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node body = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        // Constant false: never enter the then-block.
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        // Constant true: always enter the then-block (no compare or branch).
        if (Boolean.TRUE.equals(lit)) {
            genNode(body);
            return;
        }
        // (lhs op rhs): emit CPX so Z=1 iff values are equal, then branch around body when the if-condition is false.
        if (!emitConditionCompare(cond)) {
            return;
        }
        String relOp = findBoolOpName(cond);
        if ("==".equals(relOp)) {
            // Enter block when equal; skip to after if when not equal.
            genThenAfterCpx(true, body, null);
        } else if ("!=".equals(relOp)) {
            // Enter block when not equal; skip to after if when equal.
            genThenAfterCpx(false, body, null);
        } else {
            reportError("if: need == or !=");
        }
    }

    // finds the name of the boolean operator
    // returns the name of the boolean operator
    private String findBoolOpName(Tree.Node boolNode) {
        int[] opIx = new int[1];
        String op = findBoolOp(boolNode, opIx);
        return op;
    }

    // Loads left/right, executes CPX abs: Z flag is set (Z=1) when both bytes are numerically equal.
    private boolean emitConditionCompare(Tree.Node boolNode) {
        if (!"BooleanExpr".equals(boolNode.name)) {
            return false;
        }
        // find the boolean operator
        int[] opIx = new int[1];
        String op = findBoolOp(boolNode, opIx);
        // if the boolean operator is not found, return false
        if (op == null) {
            return false;
        }
        // get the left hand side and right hand side of the boolean expression
        Tree.Node lhs = boolNode.children.get(opIx[0] - 1);
        Tree.Node rhs = boolNode.children.get(opIx[0] + 1);
        // prepare the CPX setup for the left hand side and right hand side
        return prepareCpxLeftRight(lhs, rhs);
    }

    /**
     * Unconditional relative branch to {@code targetPc} using only LDA/BNE (no JMP in the instruction set).
     * {@code LDA #$01} leaves A non-zero (Z=0), so {@code BNE} always takes the branch.
     */
    private void emitUnconditionalBneTo(int targetPc) {
        emitOp("LDA #1 (then BNE always)", LDA_IMM, 1);
        emitOp("BNE rel", BNE, 0);
        relPatches.add(new RelPatch(pc() - 1, targetPc));
    }

    /**
     * Emits the then/loop body and the branches that skip it when the source-level condition is false.
     * <p>
     * Precondition: {@code prepareCpxLeftRight} just ran — <strong>Z=1 means the two compared values are
     * equal</strong> (6502 {@code CPX} semantics). {@code BNE} branches when Z=0 (values not equal).
     * </p>
     * <ul>
     *   <li>{@code equalityMeansThen == true} ({@code ==}): condition true when equal. {@code BNE} jumps past the
     *       body when not equal (condition false). Otherwise fall through into the body.</li>
     *   <li>{@code equalityMeansThen == false} ({@code !=}): condition true when not equal. First {@code BNE}
     *       jumps <em>into</em> the body when not equal; if equal, we fall through {@code LDA #$01}/{@code BNE}
     *       to skip the body (no {@code BEQ} in the ISA).</li>
     *   <li>{@code loopHeadPc != null} ({@code while}): after the body, emit an unconditional back-branch to
     *       {@code loopHeadPc} so the test runs again. {@code null} ({@code if}): no back-branch.</li>
     * </ul>
     */
    private void genThenAfterCpx(boolean equalityMeansThen, Tree.Node body, Integer loopHeadPc) {
        if (equalityMeansThen) {
            // == : want body when equal (Z=1). BNE runs when Z=0 → skip body when condition is false.
            emitOp("BNE past-then (== cond false)", BNE, 0);
            int brPast = pc() - 1;
            genNode(body);
            // while only: loop back to the start of the compare sequence.
            if (loopHeadPc != null) {
                emitUnconditionalBneTo(loopHeadPc);
            }
            // Patch: skip branch lands here — after body, or after body + back-edge for while.
            relPatches.add(new RelPatch(brPast, pc()));
        } else {
            // != : want body when not equal (Z=0). BNE branches into body; if equal, skip body via 2nd BNE.
            emitOp("BNE then-body (!= cond true)", BNE, 0);
            int brIntoThen = pc() - 1;
            emitOp("LDA #1", LDA_IMM, 1);
            emitOp("BNE past-then (!= cond false)", BNE, 0);
            int brPastEqual = pc() - 1;
            int thenStart = pc();
            genNode(body);
            if (loopHeadPc != null) {
                emitUnconditionalBneTo(loopHeadPc);
            }
            int pastThen = pc();
            relPatches.add(new RelPatch(brPastEqual, pastThen));
            relPatches.add(new RelPatch(brIntoThen, thenStart));
        }
    }

    /**
     * While-loop codegen.
     * <ul>
     *   <li>{@code while (false)} — no code (body never runs).</li>
     *   <li>{@code while (true)} — infinite loop: body then unconditional branch back to body start.</li>
     *   <li>Otherwise: {@code loopHead} points at the first byte of the <em>compare</em> each iteration.
     *       Condition true → body → branch back to {@code loopHead}. Condition false → branch to the first
     *       instruction after the whole loop (skips body and skip back-edge).</li>
     * </ul>
     */
    private void genWhile(Tree.Node n) {
        Tree.Node cond = n.children.isEmpty() ? null : n.children.get(0);
        Tree.Node body = n.children.size() > 1 ? n.children.get(1) : null;
        Boolean lit = booleanConditionLiteral(cond);
        if (Boolean.FALSE.equals(lit)) {
            return;
        }
        if (Boolean.TRUE.equals(lit)) {
            // Infinite loop: no test; jump back to first byte of body.
            int loopHead = pc();
            genNode(body);
            emitUnconditionalBneTo(loopHead);
            return;
        }
        // loopHead = start of emitted compare; each iteration re-executes from here.
        int loopHead = pc();
        if (!emitConditionCompare(cond)) {
            return;
        }
        String relOp = findBoolOpName(cond);
        if ("==".equals(relOp)) {
            genThenAfterCpx(true, body, loopHead);
        } else if ("!=".equals(relOp)) {
            genThenAfterCpx(false, body, loopHead);
        } else {
            reportError("while: need == or !=");
        }
    }

    /** {@code true} / {@code false} as a single-leaf {@code BooleanExpr}, or {@code null} if not a constant. */
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

    private String findBoolOp(Tree.Node be, int[] opIdxOut) {
        for (int i = 0; i < be.children.size(); i++) {
            String nm = be.children.get(i).name;
            if ("==".equals(nm) || "!=".equals(nm)) {
                opIdxOut[0] = i;
                return nm;
            }
        }
        return null;
    }

    /** Small integer suitable for CPX: int literals or boolean true/false as 1/0. */
    private Integer literalCompareValue(Tree.Node n) {
        if (n == null) {
            return null;
        }
        if ("BooleanExpr".equals(n.name) && n.children.size() == 1) {
            String v = n.children.get(0).name;
            if ("true".equals(v)) {
                return 1;
            }
            if ("false".equals(v)) {
                return 0;
            }
        }
        return intLiteralFromExpr(n);
    }

    /**
     * Stage values into X and scratch, then {@code CPX scratch}: Z=1 when lhs and rhs bytes are equal.
     * Supports literal–literal, literal–variable, variable–literal, variable–variable (ints/bools as 0–255).
     */
    private boolean prepareCpxLeftRight(Tree.Node lhs, Tree.Node rhs) {
        Integer lhsLit = literalCompareValue(lhs);
        Integer rhsLit = literalCompareValue(rhs);
        String lhsId = findIdInExpr(lhs);
        String rhsId = findIdInExpr(rhs);

        // Both compile-time constants: LDX rhs, scratch lhs, CPX.
        if (lhsLit != null && rhsLit != null) {
            emitOp("LDX #", LDX_IMM, rhsLit & 0xFF);
            emitOp("LDA #", LDA_IMM, lhsLit & 0xFF);
            emitAbsScratch("STA scratch", STA_ABS);
            emitAbsScratch("CPX scratch", CPX_ABS);
            return true;
        }

        // Literal on left, variable on right: scratch holds lhs, X loads rhs from memory.
        if (lhsLit != null) {
            emitOp("LDA #", LDA_IMM, lhsLit & 0xFF);
            emitAbsScratch("STA scratch", STA_ABS);
            if (rhsId == null) {
                reportError("CPX: bad rhs");
                return false;
            }
            Symbol r = lookup(rhsId);
            if (r == null) {
                return false;
            }
            emitAbsSym("LDX abs", LDX_ABS, r);
            emitAbsScratch("CPX scratch", CPX_ABS);
            return true;
        }
        // Variable on left: LDX lhs; rhs is literal (scratch) or another variable (scratch from LDA rhs).
        if (lhsId != null) {
            Symbol l = lookup(lhsId);
            if (l == null) {
                return false;
            }
            emitAbsSym("LDX abs", LDX_ABS, l);
            if (rhsLit != null) {
                emitOp("LDA #", LDA_IMM, rhsLit & 0xFF);
                emitAbsScratch("STA scratch", STA_ABS);
                emitAbsScratch("CPX scratch", CPX_ABS);
                return true;
            }
            if (rhsId != null) {
                Symbol r = lookup(rhsId);
                if (r == null) {
                    return false;
                }
                emitAbsSym("LDA abs", LDA_ABS, r);
                emitAbsScratch("STA scratch", STA_ABS);
                emitAbsScratch("CPX scratch", CPX_ABS);
                return true;
            }
        }
        reportError("CPX: need var and literal/var");
        return false;
    }

    private Integer intLiteralFromExpr(Tree.Node n) {
        if ("IntExpr".equals(n.name)) {
            for (Tree.Node c : n.children) {
                if (c.children == null || c.children.isEmpty()) {
                    Integer v = parseDigitLexeme(c.name);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return parseDigitLexeme(n.name);
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

    private Integer parseDigitLexeme(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void genIntExprValueInA(Tree.Node n) {
        if ("Expr".equals(n.name) && n.children.size() == 1) {
            genIntExprValueInA(n.children.get(0));
            return;
        }
        if (n.children == null || n.children.isEmpty()) {
            Integer v = parseDigitLexeme(n.name);
            if (v != null) {
                emitOp("LDA #", LDA_IMM, v & 0xFF);
                return;
            }
            String id = leafAsId(n);
            if (id != null) {
                Symbol s = lookup(id);
                if (s != null) {
                    emitAbsSym("LDA abs", LDA_ABS, s);
                }
                return;
            }
            reportError("bad int expr");
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
                genIntExprValueInA(n.children.get(plus - 1));
                emitAbsScratch("STA scratch", STA_ABS);
                genIntExprValueInA(n.children.get(plus + 1));
                // course table has no CLC; VM carry state is assumed suitable for simple adds
                emitAbsScratch("ADC scratch", ADC_ABS);
                return;
            }
            for (Tree.Node c : n.children) {
                genIntExprValueInA(c);
                return;
            }
        }
        for (Tree.Node c : n.children) {
            genIntExprValueInA(c);
            return;
        }
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

    /** Returns the string payload of a {@code StringExpr} AST (concatenated char leaves), or null. */
    private String stringExprLiteral(Tree.Node n) {
        if (!"StringExpr".equals(n.name)) {
            return null;
        }
        for (Tree.Node c : n.children) {
            if (c.children == null || c.children.isEmpty()) {
                return c.name;
            }
        }
        return null;
    }

    private String leafAsId(Tree.Node n) {
        if (n == null || n.children != null && !n.children.isEmpty()) {
            return null;
        }
        String nm = n.name;
        if (nm != null && nm.matches("[a-z]+") && !KEYWORDS.contains(nm)) {
            return nm;
        }
        return null;
    }

    // finds the child identifier
    // returns the child identifier
    private String childIdentifier(Tree.Node n, int leafIndex) {
        int seen = 0;
        // for each child of the node, find the id
        for (Tree.Node c : n.children) {
            String id = leafAsId(c);
            if (id != null) {
                // if the id is not null, return the id
                if (seen == leafIndex) {
                    return id;
                }
                seen++;
            }
        }
        for (Tree.Node c : n.children) {
            if (c.children != null && !c.children.isEmpty()) {
                continue;
            }
            String nm = c.name;
            if (nm != null && nm.matches("[a-z]+") && !KEYWORDS.contains(nm)) {
                if (seen == leafIndex) {
                    return nm;
                }
                seen++;
            }
        }
        return null;
    }

    // finds the id in the subtree
    // returns the id in the subtree
    private String findIdInSubtree(Tree.Node n) {
        if (n == null) {
            return null;
        }
        // get the id from the leaf
        String id = leafAsId(n);
        // if the id is not null, return the id
        if (id != null) {
            return id;
        }
        // for each child of the node, find the id in the subtree
        for (Tree.Node c : n.children) {
            String s = findIdInSubtree(c);
            // if the id is not null, return the id
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    // applies the relative branch patches to the memory
    // returns out of range if the branch is out of range
    private void applyRelativePatches() {
        for (RelPatch p : relPatches) {
            // get the next instruction
            int nextInsn = p.operandOffset + 1;
            // get the displacement
            int disp = p.targetPc - nextInsn;
            if (disp < -128 || disp > 127) {
                reportError("branch out of range disp=" + disp);
                continue;
            }
            memory[p.operandOffset] = (byte) disp;
        }
    }

    // prints the code grid in hex in a 16x16 grid
    public void printCodeGrid() {
        System.out.println("Memory: code [0.." + (layoutCodeLen > 0 ? layoutCodeLen - 1 : 0)
                + "]  stack [$" + String.format("%02X", layoutStackBase) + "..]"
                + "  heap [$" + String.format("%02X", layoutHeapBottom) + "..FF]  (else 00)");
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                int i = row * 16 + col;
                System.out.printf("%02X ", memory[i] & 0xFF);
            }
            System.out.println();
        }
    }
}
