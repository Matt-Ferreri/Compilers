    import java.util.List;
import java.util.ArrayList;
import java.util.Hashtable;


public class SemanticAnalyzer {

    // if we get an error, set this to true a new program is reached
    private boolean currentProgramHasErrors = false;

    private boolean verbose;
    public boolean hasErrors = false;

    // create a hash table to store the symbol table for the semantic analyzer
    Hashtable<Lex.characterType, String> ht = new Hashtable<>();


    // a tree of hash tables  
    Tree tree = new Tree();


    //list of tokens for the semantic analyzer,
    // since we made it this far we have no errors, in lexing or parsing 
    // so we can just use the list of tokens to perform semantic analysis
    private List<Token> tokens; 

    // if there is an error return true
    public boolean semanticErrors() {
        return hasErrors;
    }

    public void run(List<Token> tokens, boolean isVerbose) {
        this.verbose = isVerbose;
        this.tokens = tokens;
    }

}
