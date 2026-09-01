package com.boocord.music.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShuffleUtils {
  private ShuffleUtils() {
  }

  public static <T> List<T> shuffledCopy(List<T> input) {
    List<T> copy = new ArrayList<>(input);
    Collections.shuffle(copy);
    return copy;
  }
}
