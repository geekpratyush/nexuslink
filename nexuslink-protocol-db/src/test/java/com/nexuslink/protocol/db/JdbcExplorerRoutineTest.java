package com.nexuslink.protocol.db;

import com.nexuslink.plugin.ResourceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the object tree shows when a procedure or function is selected — the gap the user hit, where
 * clicking a routine produced nothing at all. Run against in-memory H2.
 */
public class JdbcExplorerRoutineTest {

    /** Backing method for the H2 alias the tests browse. */
    public static int twice(int n) { return n * 2; }

    private JdbcService service;
    private JdbcExplorer explorer;

    @BeforeEach
    void setUp() throws Exception {
        service = new JdbcService();
        service.connect("jdbc:h2:mem:explorerroutines;DB_CLOSE_DELAY=-1", "sa", "");
        service.execute("DROP ALL OBJECTS");
        service.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(50))");
        service.execute("CREATE ALIAS twice FOR \"" + JdbcExplorerRoutineTest.class.getName() + ".twice\"");
        explorer = new JdbcExplorer(service);
    }

    @AfterEach
    void tearDown() { service.close(); }

    private ResourceNode routineNode() throws Exception {
        for (ResourceNode folder : explorer.children(explorer.roots().get(0))) {
            if (!folder.id().startsWith("folder:proc") && !folder.id().startsWith("folder:func")) continue;
            List<ResourceNode> children = explorer.children(folder);
            if (!children.isEmpty()) return children.get(0);
        }
        return fail("the explorer listed no routines");
    }

    @Test
    void selectingARoutineShowsItsSignatureAndParameters() throws Exception {
        Map<String, String> details = explorer.details(routineNode());
        assertTrue(details.containsKey("Signature"), details.toString());
        assertTrue(details.get("Signature").toUpperCase().startsWith("TWICE("), details.toString());
        assertTrue(details.containsKey("Kind"));
        assertTrue(details.keySet().stream().anyMatch(k -> k.startsWith("Parameter")),
                "the parameter list should be spelled out: " + details);
    }

    @Test
    void aRoutineAlwaysHasSomethingToShowInTheSourcePane() throws Exception {
        String script = explorer.script(routineNode());
        assertFalse(script.isBlank(), "a routine with no stored body still explains itself");
        assertTrue(script.toUpperCase().contains("TWICE"), script);
    }

    @Test
    void aTableSourceIsItsDdl() throws Exception {
        ResourceNode table = new ResourceNode("table:PEOPLE", "PEOPLE", ResourceNode.Kind.TABLE, true, Map.of());
        String ddl = explorer.script(table);
        assertTrue(ddl.toUpperCase().contains("CREATE TABLE"), ddl);
        assertTrue(ddl.toUpperCase().contains("PEOPLE"), ddl);
    }

    @Test
    void anObjectWithNoDefinitionReturnsAnEmptyScript() throws Exception {
        ResourceNode column = new ResourceNode("col:PEOPLE.ID", "ID", ResourceNode.Kind.COLUMN, false, Map.of());
        assertEquals("", explorer.script(column));
    }

    @Test
    void detailsOfANonRoutineAreLeftAlone() throws Exception {
        ResourceNode column = new ResourceNode("col:PEOPLE.ID", "ID", ResourceNode.Kind.COLUMN, false,
                Map.of("Column", "ID"));
        assertEquals(Map.of("Column", "ID"), explorer.details(column));
    }

    @Test
    void theSignatureNamesTheReturnTypeOfAFunction() {
        String signature = service.routineSignature("TWICE");
        assertTrue(signature.contains("returns"), signature);
    }
}
