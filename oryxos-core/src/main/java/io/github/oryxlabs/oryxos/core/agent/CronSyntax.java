package io.github.oryxlabs.oryxos.core.agent;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.util.Locale;

/** Six-field Spring cron syntax validation, without adding Spring to core. No scheduling here. */
final class CronSyntax {
  private CronSyntax() {}

  static void validate(String cron) {
    try {
      String[] fields = cron.toUpperCase(Locale.ROOT).trim().split("\\s+");
      if (fields.length != 6) throw new IllegalArgumentException();
      int[] min = {0, 0, 0, 1, 1, 0}, max = {59, 59, 23, 31, 12, 7};
      for (int i = 0; i < 6; i++) {
        String field = fields[i];
        String[] names =
            i == 4
                ? new String[] {
                  "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
                }
                : i == 5
                    ? new String[] {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"}
                    : new String[0];
        for (int n = 0; n < names.length; n++)
          field = field.replace(names[n], "" + (i == 4 ? n + 1 : n));
        for (String part : field.split(",", -1)) {
          if ((i == 3 || i == 5) && part.equals("?")) continue;
          if (i == 3 && (part.equals("L") || part.equals("LW"))) continue;
          if (i == 3 && part.startsWith("L-")) {
            number(part.substring(2), 1, 30);
            continue;
          }
          if (i == 3 && part.endsWith("W")) {
            number(part.substring(0, part.length() - 1), 1, 31);
            continue;
          }
          if (i == 5 && part.endsWith("L")) {
            number(part.substring(0, part.length() - 1), 0, 7);
            continue;
          }
          if (i == 5 && part.contains("#")) {
            String[] nth = part.split("#", -1);
            if (nth.length != 2) throw new IllegalArgumentException();
            number(nth[0], 0, 7);
            number(nth[1], 1, 5);
            continue;
          }
          String[] step = part.split("/", -1);
          if (step.length > 2) throw new IllegalArgumentException();
          if (step.length == 2) number(step[1], 1, Integer.MAX_VALUE);
          if (step[0].equals("*")) continue;
          String[] range = step[0].split("-", -1);
          if (range.length > 2) throw new IllegalArgumentException();
          int from = number(range[0], min[i], max[i]);
          if (range.length == 2 && from > number(range[1], min[i], max[i]))
            throw new IllegalArgumentException();
        }
      }
    } catch (RuntimeException e) {
      throw OryxError.configuration("agent.schedules.cron (six fields)");
    }
  }

  private static int number(String text, int min, int max) {
    if (!text.matches("[0-9]+")) throw new IllegalArgumentException();
    int n = Integer.parseInt(text);
    if (n < min || n > max) throw new IllegalArgumentException();
    return n;
  }
}
