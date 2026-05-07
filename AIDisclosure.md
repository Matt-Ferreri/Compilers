# AI disclosure — Cursor use on this project

Cursor was used as a tool to assist with this codebase.

## What it helped with most

- **Code generation** — strongest area of contribution. After I wrote out code for the steps that I wanted done, it then took over and changed it around and finished it. I went back and then used it to explain and justify everything that was done.
- **Test cases** — useful for generating tests across pipeline stages (lex, parse, semantic, codegen).
- It did also help with semantic analysis a lot and the parser. The parser was mainly done by myself but some changes were made at the end by cursor. The semantic analysis was similar, but it did a lot more of that aspect as it helped with the symbol table a significant amount.
- The lexer was also a combination, it helped with the different states, the DFA, and formatting in the token stream, but what was done in each state was done by me with some adjustments.
-Cursor did most of part 2 of the projects other than the prompts. 
    - in Lex 
        - adds EOP to end of program if missing. This was implemented the first time I did lex.
        - implemented changing of uppercase to lowercase since uppercase letters aren't supported (doesn't change meaning, but prevent errors, does provide hints when it is done)
    - in parse: 
        - when looking for a boolop, if there was only a single = (assignment op) as opposed to == it will change it to == and provide the warning message.
    - in Semantic Analysis:
        - constant folding: does computations and puts those in AST. For example if a variable is being set to 3 + 5, it will just set 8 in the AST or a bool expreession can be computed such as 3==4, it will set to false in the AST
        - Constant propagation: records each variable’s compile-time value after assignments, with scope changes copy the map per scope so shadowing works, merges constants back to the map when leaving a block only for variables from origional scope, and rewrites reads in the AST to literal subtrees when a name’s current constant is known.
        - dead code elimiation:code that is impossible to reach, such as while(0==1) is elimiated to keep 
        - loop unrolling: loops that only run a couple of times change to repeat code so there is less branching and no looping in the assembler. Implements loop unrolling for loops that run less that 5 times 

    Code gen:
    Code done completely by cursor:
    - takes in Alan++ program, once it gets past AST converts it to java source code, prints it in terminal (and a file), runs it, and shows results
    - java code generator: takes in code, runs through code generator, variables have to be declared by scope as java needs to scope to end to be able to declare that variable name again 


## Corrections and limitations

I still needed to steer and fix the tool in several places:

- It **added opcodes that were not part of our instruction set**; those had to be removed or replaced to match the course table.
- It did not set **unused memory to `0x00`**; zero-filling the image was something to enforce because it is best practice.
- When going back and reviewing the code it did not do a good job of naming variables, I was often confused and needed it to explain.
- The AI implemented error messages within code gen that should have been caught in other stages of the compiler. The code generator should not have been catching those types of errors.
- The AI included the code in assembler which was not needed and it was never stated that is was needed. 

## Grammar / assumption pitfalls

The model was **convinced that incrementing variables was valid** in this grammar. It repeatedly suggested test cases like `x = x + 1`, which **was not valid** for our language definition.

---

*This file documents how AI tooling was used for academic transparency.*
