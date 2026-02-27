

public class Token {

    // fields needed for the token object
    // need the type of token, what the token is, and where it is
    public final Lex.characterType tokenType;
    public final String value;
    public final int line;
    public final int position;

    public Token(Lex.characterType tokenType, String value, int line, int position) {
        this.tokenType = tokenType;
        this.value = value;
        this.line = line;
        this.position = position;
    }
}