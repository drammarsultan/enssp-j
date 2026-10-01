package enssp.fixtures;

/** Small stateful module used by enSSP-J's own tests: a bounded stack of ints. */
public class BoundedStack {
    private final int[] items;
    private int size;

    public BoundedStack() { this(3); }

    public BoundedStack(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        items = new int[capacity];
    }

    public void push(int x) {
        if (size == items.length) throw new IllegalStateException("full");
        items[size++] = x;
    }

    public int pop() {
        if (size == 0) throw new IllegalStateException("empty");
        return items[--size];
    }

    public int peek() {
        if (size == 0) throw new IllegalStateException("empty");
        return items[size - 1];
    }

    public boolean isEmpty() { return size == 0; }

    public boolean isFull() { return size == items.length; }

    public int size() { return size; }
}
