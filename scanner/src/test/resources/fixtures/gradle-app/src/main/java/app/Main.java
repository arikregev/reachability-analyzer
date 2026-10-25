package app;

import com.example.vuln.Parser;

public class Main {
    public static void main(String[] args) {
        System.out.println(Parser.safe(args[0]));
    }
}
