
public class Symbol {

    public static Object variableType;
    // fields needed for the symbol object
    // need the type of symbol, what the symbol is, and where it is
    public String type;
    public boolean isInitialized;
    public boolean isUsed;
    

    public Symbol(String type, boolean isInitialized, boolean isUsed) {
        this.type = type;
        this.isInitialized = isInitialized;
        this.isUsed = isUsed;
    }
}