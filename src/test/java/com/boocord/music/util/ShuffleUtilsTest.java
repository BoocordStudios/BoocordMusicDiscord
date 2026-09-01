package com.boocord.music.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShuffleUtilsTest {
  @Test
  void returnsANewListWithTheSameValues() {
    List<Integer> input = new ArrayList<>(List.of(1, 2, 3, 4, 5));
    List<Integer> shuffled = ShuffleUtils.shuffledCopy(input);

    assertNotSame(input, shuffled);
    assertEquals(List.of(1, 2, 3, 4, 5), input);
    assertEquals(input.stream().sorted().toList(), shuffled.stream().sorted().toList());
  }
}
