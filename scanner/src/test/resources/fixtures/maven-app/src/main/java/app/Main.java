package app;

import com.example.vuln.Parser;

public class Main {
    public static void main(String[] args) {
        Runnable r = () -> System.out.println(Parser.parse(args[0]));
        r.run();
    }
}
