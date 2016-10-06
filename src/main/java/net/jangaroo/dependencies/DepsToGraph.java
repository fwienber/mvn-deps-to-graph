package net.jangaroo.dependencies;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DepsToGraph {

  private Multimap<String, String> deps;
  private Multimap<String, String> depsBeforeCollapse;
  private Multimap<String, String> inverseDeps;
  private List<Mapping> mappings;
  private Set<String> modules;
  private Map<String,String> modulesToComponents;
  private Multimap<String, String> reachableComponents;
  private Multimap<String, String> dependingComponents;
  private Multimap<String, String> componentsToAllModules;
  private Set<String> components;

  public static class Mapping {
    final String component;
    final Pattern pattern;

    public Mapping(String component, Pattern pattern) {
      this.component = component;
      this.pattern = pattern;
    }
  }

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
    computeModulesToComponents();
    computeReachableComponents();
    computeDependingComponents();
    collapseComponents();
    detectCycles();
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
    return strings[0] + ":" + strings[1];
    //return strings[0] + ":" + strings[1] + ":" + strings[2];
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
    mappings = new ArrayList<>();
    components = new HashSet<>();

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
      while (reader.ready()) {
        String line = reader.readLine();
        if (!line.startsWith("#")) {
          int index = line.indexOf('=');
          if (index != -1) {
            String component = line.substring(0, index);
            mappings.add(new Mapping(component, Pattern.compile(line.substring(index + 1))));
            components.add(component);
          }
        }
      }
    }
  }

  private String getComponent(String module) {
    String result = module;

    for (Mapping mapping : mappings) {
      if (mapping.pattern.matcher(result).matches()) {
        result = mapping.component;
        continue;
      }

      String[] strings = result.split(":");
      if (strings.length >= 2) {
        String pureName = strings[1];
        if (mapping.pattern.matcher(pureName).matches()) {
          result = mapping.component;
        }
      }
    }

    return result.equals(module) ? null : result;
  }

  private void computeModulesToComponents() {
    modulesToComponents = new HashMap<>();

    for (String module : modules) {
      String component = getComponent(module);
      if (component != null) {
        modulesToComponents.put(module, component);
      }
    }
  }

  private void computeReachableComponents() {
    reachableComponents = HashMultimap.create();

    HashSet<String> visited = new HashSet<>();
    for (String module : modules) {
      computeReachableComponents(module, visited);
    }
  }

  private void computeReachableComponents(String current, HashSet<String> visited) {
    if (visited.add(current)) {
      Set<String> joinedReachableComponents = new HashSet<>();
      if (modulesToComponents.containsKey(current)) {
        joinedReachableComponents.add(modulesToComponents.get(current));
      }

      for (String node : new ArrayList<>(deps.get(current))) {
        computeReachableComponents(node, visited);
        joinedReachableComponents.addAll(reachableComponents.get(node));
      }
      reachableComponents.putAll(current, joinedReachableComponents);
    }
  }

  private void computeDependingComponents() {
    dependingComponents = HashMultimap.create();

    HashSet<String> visited = new HashSet<>();
    for (String module : modules) {
      computeDependingComponents(module, visited);
    }
  }

  private void computeDependingComponents(String current, HashSet<String> visited) {
    if (visited.add(current)) {
      Collection<String> joinedDependingComponents = new HashSet<>();
      if (modulesToComponents.containsKey(current)) {
        joinedDependingComponents.add(modulesToComponents.get(current));
      }

      for (String node : new ArrayList<>(inverseDeps.get(current))) {
        computeDependingComponents(node, visited);
        joinedDependingComponents.addAll(dependingComponents.get(node));
      }
      dependingComponents.putAll(current, joinedDependingComponents);
    }
  }

  private void collapseComponents() {
    depsBeforeCollapse = HashMultimap.create(deps);

    componentsToAllModules = HashMultimap.create();
    for (String module : modules) {
      Set<String> moduleComponents = new HashSet<>(reachableComponents.get(module));
      moduleComponents.retainAll(dependingComponents.get(module));

      if (moduleComponents.size() > 1) {
        System.out.println(String.format("Module %s is supposed to be member of multiple components: %s", module, moduleComponents));
        for (String component : moduleComponents) {
          System.out.println(String.format("  Path to component %s is: %s", component, findPath(module, component, deps)));
          System.out.println(String.format("  Path from component %s is: %s", component, findPath(module, component, inverseDeps)));
        }
      } else if (moduleComponents.size() == 1) {
        componentsToAllModules.put(moduleComponents.iterator().next(), module);
      }
    }

    for (String component : componentsToAllModules.keySet()) {
      merge(componentsToAllModules.get(component), component);
    }
  }

  /**
   * Find a path from the given module to a module of the given component following the
   * successor relation. Return an empty list if no path could be found.
   *
   * @param module the module
   * @param component the component
   * @param successorRelation the successor relation.
   * @return the path
   */
  private List<String> findPath(String module, String component, Multimap<String, String> successorRelation) {
    Set<String> visited = new HashSet<>();
    List<String> path = new ArrayList<>();
    findPath(module, component, successorRelation, visited, path);
    return path;
  }

  private boolean findPath(String current, String component, Multimap<String, String> successorRelation, Set<String> visited, List<String> path) {
    if (visited.add(current)) {
      path.add(current);
      if (component.equals(modulesToComponents.get(current))) {
        return true;
      }

      for (String node : successorRelation.get(current)) {
        if (findPath(node, component, successorRelation, visited, path)) {
          return true;
        }
      }

      path.remove(path.size() - 1);
    }

    return false;
  }

  private void merge(Collection<String> oldNodes, String component) {
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

  private void detectCycles() {
    Set<String> visited = new HashSet<>();
    for (String node : deps.keySet()) {
      List<String> path = new ArrayList<>();
      if (detectCycles(path, node, visited)) {
        return;
      }
    }
  }

  private boolean detectCycles(List<String> path, String node, Set<String> visited) {
    if (path.contains(node)) {
      int start = path.indexOf(node);
      List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
      cycle.add(node);
      System.out.println("Cycle in component structure detected: " + cycle);
      for (int i = 1; i < cycle.size(); i++) {
        String from = cycle.get(i - 1);
        String to = cycle.get(i);
        System.out.println("Possible path from " + from + " to " + to + ": " + getPathFromTo(from, to));
      }
      return true;
    }
    if (visited.add(node)) {
      path.add(node);
      Collection<String> successors = deps.get(node);
      for (String successor : successors) {
        if (detectCycles(path, successor, visited)) {
          return true;
        }
      }
      path.remove(path.size() - 1);
    }
    return false;
  }

  private List<String> getPathFromTo(String from, String to) {
    if (components.contains(from)) {
      for (String fromModule : componentsToAllModules.get(from)) {
        List<String> path = getPathFromTo(fromModule, to);
        if (path != null) {
          return path;
        }
      }
      return null;
    } else if (components.contains(to)) {
      List<String> path = findPath(from, to, depsBeforeCollapse);
      if (!path.isEmpty()) {
        return path;
      }
      return null;
    } else {
      return Arrays.asList(from, to);
    }
  }

  private void removeTransitiveDependencies() {
    for (String node : new ArrayList<>(deps.keySet())) {
      removeTransitiveDependencies(node, node, 0, new HashSet<String>());
    }
  }

  private void removeTransitiveDependencies(String current, String root, int depth, Set<String> visited) {
    if (depth > 1) {
      deps.remove(root, current);
      inverseDeps.remove(current, root);
    }
    if (visited.add(current)) {
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
      writeNode(writer, nodeId);
    }

    for (Map.Entry<String, String> entry : deps.entries()) {
      writeEdge(writer, entry.getKey(), entry.getValue());
    }

    writer.println("</graphml>");
  }

  private void writeEdge(PrintWriter writer, String source, String target) {
    writer.println("    <edge source=\"" + source + "\" target=\"" + target + "\"/>");
  }

  private void writeNode(PrintWriter writer, String nodeId) {
    StringBuilder builder = new StringBuilder();
    String nodeLabel = toLabel(nodeId);
    int maxPos = nodeLabel.length();
    int lines = 1;
    builder.append(nodeLabel);
    List<String> parts = new ArrayList<>();
    for (String partId : componentsToAllModules.get(nodeId)) {
      parts.add(toLabel(partId));
    }
    Collections.sort(parts);
    if (!parts.isEmpty()) {
      builder.append("\n\n");
      lines = lines + 2;
      int pos = 0;
      boolean first = true;
      for (String part : parts) {
        if (first) {
          first = false;
        } else {
          builder.append(",");
          pos = pos + 1;
          if (pos > 50) {
            builder.append("\n");
            lines++;
            pos = 0;
          } else {
            builder.append(" ");
            pos = pos + 1;
          }
        }
        builder.append(part);
        pos = pos + part.length();
        maxPos = Math.max(maxPos, pos);
      }
    }
    String color = components.contains(nodeId) ? "88ff88" : "ffffff";

    writer.println("    <node id=\"" + nodeId + "\">");
    writer.println("      <data key=\"d6\">");
    writer.println("        <y:ShapeNode>");
    writer.println("        <y:Fill color=\"#" + color + "\" transparent=\"false\"/>");
    writer.println("          <y:Geometry height=\"" + (10 + 14 * lines) + "\" width=\"" + (30 + 6 * maxPos) + "\"/>\n");
    writer.println("          <y:NodeLabel>" + builder + "</y:NodeLabel>");
    writer.println("        </y:ShapeNode>");
    writer.println("      </data>");
    writer.println("    </node>");
  }

}
