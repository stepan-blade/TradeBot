package com.example.demo.utils;

public class Formatter {
    public static String formatSymbol(String s) {
        return s == null ? "" : s.replace("USDT", "/USDT");
    }
}
