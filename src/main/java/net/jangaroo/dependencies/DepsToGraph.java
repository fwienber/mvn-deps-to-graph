package net.jangaroo.dependencies;

import com.google.common.base.Charsets;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DepsToGraph {
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.out.println("Usage: java -jar ... <DEPSFILE> <CHANGESFILE> <OUTFILE>");
    } else {
      Multimap<String, String> deps = HashMultimap.create();
      analyzeDepsFile(new File(args[0]), deps);
      Map<String,Long> changes = new HashMap<>();
      analyzeChangesFile(new File(args[1]), changes);

      Multimap<String, String> coreDeps = excludingTransitive(deps);

      File outFile = new File(args[2]);
      try (PrintWriter writer = new PrintWriter(new FileWriter(outFile))) {
        writeGraph(writer, deps, changes);
      }
    }
  }

  private static Multimap<String, String> excludingTransitive(Multimap<String, String> deps) {
    Multimap<String, String> result = HashMultimap.create();
    for (String node : new ArrayList<>(deps.keySet())) {
      traverse(node, node, 0, deps, new HashSet<String>());
    }

    return result;
  }

  private static void traverse(String current, String root, int depth, Multimap<String, String> deps, Set<String> visited) {
    if (!visited.add(current)) {
      return;
    }
    if (depth > 1) {
      deps.remove(root, current);
    }
    for (String node : new ArrayList<>(deps.get(current))) {
      traverse(node, root, depth + 1, deps, visited);
    }
  }

  private static void analyzeDepsFile(File file, Multimap<String, String> deps) throws IOException {
    List<String> lines = Files.readLines(file, Charsets.UTF_8);

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String[] strings = line.split(" ");
      if (strings.length == 2) {
        deps.put(strings[0], strings[1]);
      }
    }
  }

  private static void analyzeChangesFile(File file, Map<String, Long> changes) throws IOException {
    List<String> lines = Files.readLines(file, Charsets.UTF_8);

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String[] strings = line.split(" ");
      if (strings.length == 2) {
        changes.put(strings[0], Long.parseLong(strings[1]));
      }
    }
  }

  private static void writeGraph(PrintWriter writer, Multimap<String, String> deps, Map<String, Long> changes) {
    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    writer.println("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:java=\"http://www.yworks.com/xml/yfiles-common/1.0/java\" xmlns:sys=\"http://www.yworks.com/xml/yfiles-common/markup/primitives/2.0\" xmlns:x=\"http://www.yworks.com/xml/yfiles-common/markup/2.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:y=\"http://www.yworks.com/xml/graphml\" xmlns:yed=\"http://www.yworks.com/xml/yed/3\" xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd\">");
    writer.println("<key for=\"node\" id=\"d6\" yfiles.type=\"nodegraphics\"/>");

    Set<String> nodes = new HashSet<>();
    nodes.addAll(deps.keySet());
    nodes.addAll(deps.values());
    for (String nodeId : nodes) {
      Long count = changes.get(nodeId);
      String color = makeColor(count == null || count == 0 ? 0 : Math.min(510, (int)(Math.log(count) * 50 + 1)));
      String label = nodeId.substring(nodeId.lastIndexOf('/') + 1);

      writer.println("    <node id=\"" + nodeId + "\">");
      writer.println("      <data key=\"d6\">");
      writer.println("        <y:ShapeNode>");
      writer.println("        <y:Fill color=\"#" + color + "\" transparent=\"false\"/>");
      writer.println("          <y:Geometry height=\"20.0\" width=\"" + 8 * label.length() + "\"/>\n");
      writer.println("          <y:NodeLabel>" + label + "</y:NodeLabel>");
      writer.println("        </y:ShapeNode>");
      writer.println("      </data>");
      writer.println("    </node>");
    }
    for (Map.Entry<String, String> entry : deps.entries()) {
      writer.println("    <edge source=\"" + entry.getKey() + "\" target=\"" + entry.getValue() + "\"/>");
    }
    writer.println("</graphml>");
  }

  private static String makeColor(int color) {
    return toTwoByteHex(255) +
            toTwoByteHex(Math.max(0, Math.min(510 - color, 255))) +
            toTwoByteHex(Math.max(0, Math.min(255 - color, 255)));
  }

  private static String toTwoByteHex(int i) {
    return (i < 16 ? "0" : "") + Integer.toHexString(i);
  }
}
