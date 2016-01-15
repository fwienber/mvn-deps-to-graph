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
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassDependencies {

  public static final String DEPENDENT_PREFIX = "  <file path=\"$PROJECT_DIR$/ui/editor-sdk/editor-components/src/main/joo/";
  public static final String DEPENDENCY_PREFIX = "    <dependency path=\"$PROJECT_DIR$/ui/editor-sdk/editor-components/src/main/joo/";

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.out.println("Usage: java -jar ... <BASEDIR> <OUTFILE>");
    } else {
      Multimap<String, String> requires = HashMultimap.create();
      analyzeFile(new File(args[0]), requires);

      Collection<Set<String>> sccs = GraphUtil.stronglyConnectedComponent(requires.asMap());
      Map<String, Set<String>> nodeToSCC = new HashMap<>();
      for (Set<String> scc : sccs) {
        for (String node : scc) {
          nodeToSCC.put(node, scc);
        }
      }

      System.out.println("Analyzing " + sccs.size() + " SCCs");

      Multimap<String, String> reducedRequires = HashMultimap.create();
      Collection<Map.Entry<String, String>> entries = requires.entries();
      for (Map.Entry<String, String> entry : entries) {
        String dependent = entry.getKey();
        String dependency = entry.getValue();
        Set<String> dependentSCC = new HashSet<>(nodeToSCC.get(dependent));

        Set<String> visited = new HashSet<>(dependentSCC);
        Deque<String> todo = new LinkedList<>();
        for (String coDependent : dependentSCC) {
          for (String candidate : requires.get(coDependent)) {
            Set<String> coSuccessors = nodeToSCC.get(candidate);
            if (!dependentSCC.contains(candidate) && !coSuccessors.contains(dependency)) {
              visited.addAll(coSuccessors);
              for (String coSuccessor : coSuccessors) {
                todo.addAll(requires.get(coSuccessor));
              }
            }
          }
        }

        boolean found = false;
        while (!todo.isEmpty()) {
          String node = todo.removeFirst();
          if (visited.add(node)) {
            if (node.equals(dependency)) {
              found = true;
              break;
            }
            todo.addAll(requires.get(node));
          }
        }

        if (!found) {
          reducedRequires.put(dependent, dependency);
        } else {
          System.out.println("dropped " + dependent + " -> " +dependency);
        }
      }

      File outFile = new File(args[1]);
      try (PrintWriter writer = new PrintWriter(new FileWriter(outFile))) {
        writeGraph(writer, requires, nodeToSCC);
      }
    }
  }

  private static void analyzeFile(File file, Multimap<String, String> requires) throws IOException {
    List<String> lines = Files.readLines(file, Charsets.UTF_8);

    String dependent = null;

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith(DEPENDENT_PREFIX)) {
        dependent = extractName(line, DEPENDENT_PREFIX);
      }
      if (line.startsWith(DEPENDENCY_PREFIX)) {
        String dependency = extractName(line, DEPENDENCY_PREFIX);
        if (dependent != null && dependency != null && !dependent.equals(dependency)) {
          requires.put(dependent, dependency);
        }
      }
    }
  }

  private static String extractName(String line, String prefix) {
    int endIndex = line.lastIndexOf('.');
    if (endIndex > prefix.length()) {
      String path = line.substring(prefix.length(), endIndex);
      String className = path.replace('/', '.');
      int dotPos = className.lastIndexOf('.');
      if (dotPos != -1) {
        String packageName = className.substring(0, dotPos);
        String[] parts = packageName.split("[.]");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length - 1 && i < 4; i++) {
          parts[i] = parts[i].substring(0, 1);
        }
        for (int i = 0; i < parts.length; i++) {
          if (i > 0) {
            builder.append('.');
          }
          builder.append(parts[i]);
        }
        return builder.toString();
      }
    }
    return null;
  }

  private static void writeGraph(PrintWriter writer, Multimap<String, String> requires, Map<String, Set<String>> nodeToSCC) {
    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    writer.println("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:java=\"http://www.yworks.com/xml/yfiles-common/1.0/java\" xmlns:sys=\"http://www.yworks.com/xml/yfiles-common/markup/primitives/2.0\" xmlns:x=\"http://www.yworks.com/xml/yfiles-common/markup/2.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:y=\"http://www.yworks.com/xml/graphml\" xmlns:yed=\"http://www.yworks.com/xml/yed/3\" xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd\">");
    writer.println("<key for=\"node\" id=\"d6\" yfiles.type=\"nodegraphics\"/>");

    Set<String> nodes = new HashSet<>();
    nodes.addAll(requires.keySet());
    nodes.addAll(requires.values());
    for (String nodeId : nodes) {
      int colorHash = Math.abs(nodeToSCC.get(nodeId).hashCode() % 300);

      String color = Integer.toHexString(Math.max(0, Math.min(Math.abs(colorHash - 150), 100)) + 155) +
              Integer.toHexString(Math.max(0, Math.min(Math.abs((colorHash + 100) % 300 - 150), 100)) + 155) +
              Integer.toHexString(Math.max(0, Math.min(Math.abs((colorHash + 200) % 300 - 150), 100)) + 155);

      writer.println("    <node id=\"" + nodeId + "\">");
      writer.println("      <data key=\"d6\">");
      writer.println("        <y:ShapeNode>");
      writer.println("        <y:Fill color=\"#" + color + "\" transparent=\"false\"/>");
      writer.println("          <y:Geometry height=\"20.0\" width=\"" + 8 * nodeId.length() + "\"/>\n");
      writer.println("          <y:NodeLabel>" + nodeId + "</y:NodeLabel>");
      writer.println("        </y:ShapeNode>");
      writer.println("      </data>");
      writer.println("    </node>");
    }
    for (Map.Entry<String, String> entry : requires.entries()) {
      writer.println("    <edge source=\"" + entry.getKey() + "\" target=\"" + entry.getValue() + "\"/>");
    }
    writer.println("</graphml>");
  }
}
