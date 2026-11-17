// Makes src/iosTest/resources/format-java.txt: values and what java.util.Formatter makes of them,
// for FormatTest (the iOS String.format must give the same). Every 30th line of the full output is kept.
//   javac -d /tmp FormatGen.java && java -cp /tmp FormatGen | awk 'NR<=60 || NR%30==0' > ../src/iosTest/resources/format-java.txt
import java.util.*;
public class FormatGen { public static void main(String[] a) {
  Random r = new Random(11); StringBuilder sb = new StringBuilder();
  double[] edge = {0.05, 0.15, 0.25, 0.35, 1.005, 2.675, 9.95, 9.995, 0.0, -0.0, -0.04, -0.05, 1e-7, 123456.789, 0.5, 1.5, 2.5, -2.5, 1e10};
  List<Double> xs = new ArrayList<>(); for (double e : edge) xs.add(e);
  for (int i = 0; i < 20000; i++) { double x = (r.nextDouble() - 0.3) * Math.pow(10, r.nextInt(7) - 3); xs.add(x); xs.add((double)(float)x); xs.add(Math.round(x * 1000) / 1000.0 + 0.0005); }
  for (double x : xs) for (String f : new String[]{"%.1f","%.2f","%.3f"}) {
    boolean isF = (double)(float)x == x;
    sb.append(Double.doubleToRawLongBits(x)).append(' ').append(f).append(' ').append(String.format(Locale.US, f, x));
    if (isF) sb.append(' ').append(String.format(Locale.US, f, (float)x)); sb.append('\n'); }
  System.out.print(sb);
}}
