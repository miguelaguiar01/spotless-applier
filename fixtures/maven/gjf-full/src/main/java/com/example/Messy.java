package com.example;

import java.util.List;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.Map;
import javax.annotation.Nullable;
import org.example.Other;

/** A badly formatted class. */
public class Messy<T extends Comparable<T>> {
  private final   List<String> names = new ArrayList<>();
    @Nullable private Map<String,String> cache;

    public Messy( @Nullable Map<String,String> cache ){ this.cache=cache; }

  public static String longString() {
      return "this is a rather long string literal that google-java-format with reflowLongStrings enabled will happily split into pieces";
  }

  // spotless:off
  public   int   keep   =    1 ;
  // spotless:on

  @Override public String toString() { return "Messy[" + names + "]" + Other.VALUE; }
}
