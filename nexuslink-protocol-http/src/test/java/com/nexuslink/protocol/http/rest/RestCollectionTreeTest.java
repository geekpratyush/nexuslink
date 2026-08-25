package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestCollectionTreeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CollectionNode req(String name) {
        return CollectionNode.request(name, JSON.createObjectNode().put("url", "https://x/" + name));
    }

    private List<CollectionNode> forest() {
        List<CollectionNode> roots = new ArrayList<>();
        CollectionNode billing = CollectionNode.folder("Billing");
        CollectionNode v2 = CollectionNode.folder("v2");
        v2.children.add(req("Create invoice"));
        billing.children.add(v2);
        billing.children.add(req("Ping"));
        roots.add(billing);
        return roots;
    }

    @Test
    void findsNodesAtAnyDepth() {
        List<CollectionNode> roots = forest();
        String id = roots.get(0).children.get(0).children.get(0).id;
        assertEquals("Create invoice", RestCollectionTree.find(roots, id).orElseThrow().name);
        assertTrue(RestCollectionTree.find(roots, "nope").isEmpty());
        assertTrue(RestCollectionTree.find(roots, null).isEmpty());
    }

    @Test
    void pathNamesEveryAncestor() {
        List<CollectionNode> roots = forest();
        String id = roots.get(0).children.get(0).children.get(0).id;
        assertEquals("Billing / v2 / Create invoice", RestCollectionTree.path(roots, id));
        assertEquals("", RestCollectionTree.path(roots, "nope"));
    }

    @Test
    void parentOfIsEmptyForARoot() {
        List<CollectionNode> roots = forest();
        assertTrue(RestCollectionTree.parentOf(roots, roots.get(0).id).isEmpty());
        assertEquals("Billing",
                RestCollectionTree.parentOf(roots, roots.get(0).children.get(1).id).orElseThrow().name);
    }

    @Test
    void addRejectsARequestAsParent() {
        List<CollectionNode> roots = forest();
        String requestId = roots.get(0).children.get(1).id;
        assertFalse(RestCollectionTree.add(roots, requestId, req("Nested")));
        assertTrue(RestCollectionTree.add(roots, null, CollectionNode.folder("Second")));
        assertEquals(2, roots.size());
    }

    @Test
    void removeTakesTheWholeSubtree() {
        List<CollectionNode> roots = forest();
        String v2 = roots.get(0).children.get(0).id;
        assertTrue(RestCollectionTree.remove(roots, v2));
        assertEquals(1, roots.get(0).children.size());
        assertFalse(RestCollectionTree.remove(roots, v2));
    }

    @Test
    void renameIgnoresBlankNames() {
        List<CollectionNode> roots = forest();
        assertFalse(RestCollectionTree.rename(roots, roots.get(0).id, "  "));
        assertTrue(RestCollectionTree.rename(roots, roots.get(0).id, " Invoicing "));
        assertEquals("Invoicing", roots.get(0).name);
    }

    @Test
    void moveRefusesAFolderIntoItsOwnSubtree() {
        List<CollectionNode> roots = forest();
        CollectionNode billing = roots.get(0);
        String v2 = billing.children.get(0).id;
        assertFalse(RestCollectionTree.move(roots, billing.id, v2, 0));
        assertFalse(RestCollectionTree.move(roots, billing.id, billing.id, 0));
        assertEquals(1, roots.size());
    }

    @Test
    void moveReparentsAndClampsTheIndex() {
        List<CollectionNode> roots = forest();
        CollectionNode billing = roots.get(0);
        String ping = billing.children.get(1).id;
        String v2 = billing.children.get(0).id;
        assertTrue(RestCollectionTree.move(roots, ping, v2, 99));
        assertEquals(1, billing.children.size());
        assertEquals("Ping", RestCollectionTree.find(roots, v2).orElseThrow().children.get(1).name);
        // …and back out to the top level
        assertTrue(RestCollectionTree.move(roots, ping, null, 0));
        assertEquals(2, roots.size());
        assertEquals("Ping", roots.get(0).name);
    }

    @Test
    void requestsUnderWalksDepthFirstInDisplayOrder() {
        List<CollectionNode> roots = forest();
        assertEquals(List.of("Create invoice", "Ping"),
                RestCollectionTree.requestsUnder(roots).stream().map(n -> n.name).toList());
        assertEquals(List.of("Create invoice"),
                RestCollectionTree.requestsUnder(roots.get(0).children.get(0)).stream().map(n -> n.name).toList());
        // A request node stands for itself, so running "this request" reuses the same call.
        CollectionNode ping = roots.get(0).children.get(1);
        assertEquals(List.of("Ping"),
                RestCollectionTree.requestsUnder(ping).stream().map(n -> n.name).toList());
    }

    @Test
    void uniqueNameIncrementsInsteadOfStacking() {
        List<CollectionNode> siblings = new ArrayList<>(List.of(req("Ping")));
        assertEquals("Pong", RestCollectionTree.uniqueName(siblings, "Pong"));
        assertEquals("Ping copy", RestCollectionTree.uniqueName(siblings, "Ping"));
        siblings.add(req("Ping copy"));
        assertEquals("Ping copy 2", RestCollectionTree.uniqueName(siblings, "Ping copy"));
        siblings.add(req("Ping copy 2"));
        assertEquals("Ping copy 3", RestCollectionTree.uniqueName(siblings, "Ping copy 2"));
    }

    @Test
    void copyIsDeepAndReassignsIds() {
        List<CollectionNode> roots = forest();
        CollectionNode original = roots.get(0);
        CollectionNode clone = original.copy();
        assertFalse(clone.id.equals(original.id));
        assertFalse(clone.children.get(0).id.equals(original.children.get(0).id));
        clone.children.get(0).name = "renamed";
        assertEquals("v2", original.children.get(0).name);
        assertEquals("https://x/Create invoice",
                clone.children.get(0).children.get(0).request.path("url").asText());
    }
}
