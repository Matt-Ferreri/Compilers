import java.util.ArrayList;
import java.util.List;

public class Tree {

    // Node class
    static class Node {
        String name;
        List<Node> children;
        Node parent;

        Node(String name) {
            this.name = name;
            this.children = new ArrayList<>();
            this.parent = null;
        }
    }

    // Attributes
    private Node root = null;
    private Node cur = null;

    // Add a node: kind in {branch, leaf}
    public void addNode(String name, String kind) {
        Node node = new Node(name);

        // Check if root
        if (root == null) {
            root = node;
            cur = node;
        } else {
            node.parent = cur;
            cur.children.add(node);
        }

        // If branch node, update current pointer
        if (kind.equals("branch")) {
            cur = node;
        }
    }

    // Move up to parent
    public void endChildren() {
        if (cur != null && cur.parent != null) {
            cur = cur.parent;
        } else {
            // Error case (should not normally happen)
            System.out.println("Warning: Attempted to move above root.");
        }
    }

    // String representation
    public String toString() {
        StringBuilder traversalResult = new StringBuilder();

        expand(root, 0, traversalResult);

        return traversalResult.toString();
    }

    // Recursive helper
    private void expand(Node node, int depth, StringBuilder traversalResult) {
        for (int i = 0; i < depth; i++) {
            traversalResult.append("-");
        }

        // Leaf node
        if (node.children == null || node.children.size() == 0) {
            traversalResult.append("[").append(node.name).append("]");
            traversalResult.append("\n");
        } else {
            // Branch node
            traversalResult.append("<").append(node.name).append("> \n");

            for (Node child : node.children) {
                expand(child, depth + 1, traversalResult);
            }
        }
    }
}