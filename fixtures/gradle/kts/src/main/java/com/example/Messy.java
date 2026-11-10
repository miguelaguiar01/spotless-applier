package com.example;

import java.util.List;
import com.google.common.base.Strings;
import java.util.ArrayList;
import javax.annotation.Nullable;

public class Messy {
  private final   List<String> names = new ArrayList<>();
    @Nullable private String cache;
  public static String longString() {
      return "this is a rather long string literal that google-java-format with reflowLongStrings enabled will happily split into pieces";
  }
  @Override public String toString() { return "Messy[" + names + "]"; }
}
