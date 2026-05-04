public class staticVar {
    public static Object staticVars;
    // static variables are stored in the stack
    // they get a memory address when they all of the code is generated, a scope, a name, and an offset

    int[][] memory = new int[16][16];
    public int scope;
    public String name;
    public int offset;

    public staticVar(int scope, String name, int offset) {
        this.scope = scope;
        this.name = name;
        this.offset = offset;
    }
    
}
