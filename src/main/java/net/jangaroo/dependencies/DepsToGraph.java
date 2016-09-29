package net.jangaroo.dependencies;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

public class DepsToGraph {

  private Multimap<String, String> deps;
  private Multimap<String, String> inverseDeps;
  private Map<String, Pattern> componentPatterns;
  private Set<String> modules;
  private Set<String> components;

  /** @noinspection UseOfSystemOutOrSystemErr*/
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.out.println("Usage: java -jar ... <DEPSFILE> <COMPFILE> <OUTFILE>");
      System.out.println();
      System.out.println("DEPSFILE: the output from 'mvn dependency:tree'");
      System.out.println("COMPFILE: a property file mapping module names to component names");
      System.out.println("OUTFILE: the target *.graphml file");
    } else {
      new DepsToGraph().execute(args[0], args[1], args[2]);
    }
  }

  private void execute(String depsFileName, String compFileName, String outFileName) throws IOException {
    analyzeDepsFile(new File(depsFileName));
    analyzeComponentFile(new File(compFileName));
    collapseComponents();
    removeTransitiveDependencies();

    File outFile = new File(outFileName);
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8"))) {
      writeGraph(writer, deps);
    }
  }

  private String column(String line, int col) {
    String[] strings = line.split(" ");
    return strings[col];
  }

  private String moduleColumn(String line, int col) {
    return toModuleId(column(line, col));
  }

  private String toModuleId(String mvnId) {
    String[] strings = mvnId.split(":");
    return strings[0] + ":" + strings[1] + ":" + strings[2];
  }

  private String toLabel(String id) {
    if (components.contains(id)) {
      return id;
    }
    String[] strings = id.split(":");
    return strings[1];
  }

  private boolean isRelevant(String moduleId) {
    return moduleId.startsWith("com.coremedia.");
  }

  private void analyzeDepsFile(File file) throws IOException {
    deps = HashMultimap.create();
    inverseDeps = HashMultimap.create();

    String module = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
      while (reader.ready()) {
        String line = reader.readLine();
        if (isHeaderLine(line)) {
          module = moduleColumn(reader.readLine(), 1);
          continue;
        }

        if (module != null && isDependencyLine(line)) {
          String dependency = moduleColumn(line, 2);
          if (isRelevant(dependency)) {
            deps.put(module, dependency);
            inverseDeps.put(dependency, module);
          }
          continue;
        }

        if (!isNestedDependencyLine(line)) {
          module = null;
        }
      }
    }

    modules = new HashSet<>();
    modules.addAll(deps.keySet());
    modules.addAll(deps.values());
  }

  private boolean isNestedDependencyLine(String line) {
    return line.startsWith("[INFO] | ");
  }

  private boolean isHeaderLine(String line) {
    return line.startsWith("[INFO] --- maven-dependency-plugin:");
  }

  private boolean isDependencyLine(String line) {
    return line.startsWith("[INFO] +- ") || line.startsWith("[INFO] \\- ");
  }

  private void analyzeComponentFile(File file) throws IOException {
    componentPatterns = new LinkedHashMap<>();

    Properties properties = new Properties();
    try (InputStream stream = new FileInputStream(file)) {
      properties.load(stream);
    }
    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      componentPatterns.put((String)entry.getKey(), Pattern.compile(((String)entry.getValue()).trim()));
    }

    components = new HashSet<>(componentPatterns.keySet());
  }

  private String getComponent(String module) {
    if (components.contains(module)) {
      return module;
    }

    for (Map.Entry<String, Pattern> entry : componentPatterns.entrySet()) {
      if (entry.getValue().matcher(module).matches()) {
        return entry.getKey();
      }
    }

    String[] strings = module.split(":");
    if (strings.length == 3) {
      String pureName = strings[1];
      for (Map.Entry<String, Pattern> entry : componentPatterns.entrySet()) {
        if (entry.getValue().matcher(pureName).matches()) {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  private void collapseComponents() {
    for (String module : new ArrayList<>(modules)) {
      // Guard against concurrent modifications.
      if (modules.contains(module)) {
        String component = getComponent(module);
        if (component != null) {
          collapseNode(module, component);
        }
      }
    }

    for (String component : components) {
      collapseNode(component, component);
    }
  }

  private void collapseNode(String module, String component) {
    Set<String> collapsibleNodes = new HashSet<>();
    List<String> path = new ArrayList<>();
    findCollapsibleComponents(module, component, collapsibleNodes, new HashSet<String>(), path);

    merge(collapsibleNodes, component);
  }

  private void findCollapsibleComponents(String current, String component, Set<String> collapsibleNodes, HashSet<String> visited, List<String> path) {
    path.add(current);

    String currentComponent = getComponent(current);
    if (component.equals(currentComponent) || component.equals(current)) {
      collapsibleNodes.addAll(path);
    }

    if (visited.add(current)) {
      for (String node : new ArrayList<>(deps.get(current))) {
        findCollapsibleComponents(node, component, collapsibleNodes, visited, path);
      }

      if (collapsibleNodes.contains(current)) {
        if (currentComponent != null && !currentComponent.equals(component)) {
          System.out.println(String.format("Node %s is assigned to component %s, which is part of the chain %s of component %s.",
                  current,
                  currentComponent,
                  path,
                  component));
        } else if (components.contains(current) && !current.equals(component)) {
          System.out.println(String.format("Component %s part of the chain %s of component %s.",
                  current,
                  path,
                  component));
        }
      }
    }

    path.remove(path.size() - 1);
  }

  private void merge(Set<String> oldNodes, String component) {
    Set<String> allSuccessors = new HashSet<>();
    Set<String> allPredecessors = new HashSet<>();

    for (String oldNode : oldNodes) {
      Collection<String> successors = deps.removeAll(oldNode);
      allSuccessors.addAll(successors);
      for (String successor : successors) {
        inverseDeps.remove(successor, oldNode);
      }

      Collection<String> predecessors = inverseDeps.removeAll(oldNode);
      allPredecessors.addAll(predecessors);
      for (String predecessor : predecessors) {
        deps.remove(predecessor, oldNode);
      }
    }

    allSuccessors.remove(component);
    allSuccessors.removeAll(oldNodes);
    allPredecessors.remove(component);
    allPredecessors.removeAll(oldNodes);

    for (String successor : allSuccessors) {
      deps.put(component, successor);
      inverseDeps.put(successor, component);
    }
    for (String predecessor : allPredecessors) {
      deps.put(predecessor, component);
      inverseDeps.put(component, predecessor);
    }

    modules.removeAll(oldNodes);
    modules.add(component);
  }

  private void removeTransitiveDependencies() {
    for (String node : new ArrayList<>(deps.keySet())) {
      removeTransitiveDependencies(node, node, 0, new HashSet<String>());
    }
  }

  private void removeTransitiveDependencies(String current, String root, int depth, Set<String> visited) {
    if (visited.add(current)) {
      if (depth > 1) {
        deps.remove(root, current);
        inverseDeps.remove(current, root);
      }
      for (String node : new ArrayList<>(deps.get(current))) {
        removeTransitiveDependencies(node, root, depth + 1, visited);
      }
    }
  }

  private void writeGraph(PrintWriter writer, Multimap<String, String> deps) {
    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    writer.println("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:java=\"http://www.yworks.com/xml/yfiles-common/1.0/java\" xmlns:sys=\"http://www.yworks.com/xml/yfiles-common/markup/primitives/2.0\" xmlns:x=\"http://www.yworks.com/xml/yfiles-common/markup/2.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:y=\"http://www.yworks.com/xml/graphml\" xmlns:yed=\"http://www.yworks.com/xml/yed/3\" xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd\">");
    writer.println("<key for=\"node\" id=\"d6\" yfiles.type=\"nodegraphics\"/>");

    for (String nodeId : modules) {
      String label = toLabel(nodeId);
      String color = components.contains(nodeId) ? "88ff88" : "ffffff";

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
}
