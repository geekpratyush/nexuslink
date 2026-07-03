package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDiffTest {

    private static final String BASE = """
            {"type":"record","name":"User","fields":[
              {"name":"id","type":"long"},
              {"name":"name","type":"string"}
            ]}""";

    @Test
    void detectsAddedField() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"id","type":"long"},
                  {"name":"name","type":"string"},
                  {"name":"email","type":"string"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        List<SchemaDiff.FieldChange> added = diff.changes(SchemaDiff.ChangeKind.ADDED);
        assertEquals(1, added.size());
        SchemaDiff.FieldChange c = added.get(0);
        assertEquals("email", c.field());
        assertNull(c.oldType());
        assertEquals("string", c.newType());
        assertEquals(2, diff.unchanged());
    }

    @Test
    void detectsRemovedField() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"id","type":"long"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        List<SchemaDiff.FieldChange> removed = diff.changes(SchemaDiff.ChangeKind.REMOVED);
        assertEquals(1, removed.size());
        SchemaDiff.FieldChange c = removed.get(0);
        assertEquals("name", c.field());
        assertEquals("string", c.oldType());
        assertNull(c.newType());
    }

    @Test
    void detectsTypeChange() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"id","type":"string"},
                  {"name":"name","type":"string"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        List<SchemaDiff.FieldChange> changed = diff.changes(SchemaDiff.ChangeKind.TYPE_CHANGED);
        assertEquals(1, changed.size());
        SchemaDiff.FieldChange c = changed.get(0);
        assertEquals("id", c.field());
        assertEquals("long", c.oldType());
        assertEquals("string", c.newType());
    }

    @Test
    void nullabilityChangeSurfacesAsTypeChanged() {
        String old = """
                {"type":"record","name":"U","fields":[
                  {"name":"nick","type":"string"}
                ]}""";
        String next = """
                {"type":"record","name":"U","fields":[
                  {"name":"nick","type":["null","string"]}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(old, next);
        List<SchemaDiff.FieldChange> changed = diff.changes(SchemaDiff.ChangeKind.TYPE_CHANGED);
        assertEquals(1, changed.size());
        SchemaDiff.FieldChange c = changed.get(0);
        assertEquals("nick", c.field());
        assertEquals("string", c.oldType());
        assertEquals("union[null,string]", c.newType());
    }

    @Test
    void identicalSchemasHaveNoChanges() {
        SchemaDiff diff = SchemaDiff.between(BASE, BASE);
        assertFalse(diff.hasChanges());
        assertTrue(diff.changes().isEmpty());
        assertEquals(2, diff.unchanged());
        assertTrue(diff.isCompatible());
    }

    @Test
    void unionIsCanonicalisedPreservingOrder() {
        String old = """
                {"type":"record","name":"R","fields":[
                  {"name":"v","type":["null","string"]}
                ]}""";
        String next = """
                {"type":"record","name":"R","fields":[
                  {"name":"v","type":["string","null"]}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(old, next);
        SchemaDiff.FieldChange c = diff.changes(SchemaDiff.ChangeKind.TYPE_CHANGED).get(0);
        assertEquals("union[null,string]", c.oldType());
        assertEquals("union[string,null]", c.newType());
    }

    @Test
    void nestedTypeObjectReducesToItsType() {
        String old = """
                {"type":"record","name":"R","fields":[
                  {"name":"tags","type":{"type":"array","items":"string"}}
                ]}""";
        String next = """
                {"type":"record","name":"R","fields":[
                  {"name":"tags","type":{"type":"map","values":"string"}}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(old, next);
        SchemaDiff.FieldChange c = diff.changes(SchemaDiff.ChangeKind.TYPE_CHANGED).get(0);
        assertEquals("array", c.oldType());
        assertEquals("map", c.newType());
    }

    @Test
    void isCompatibleTrueForAdditionsOnly() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"id","type":"long"},
                  {"name":"name","type":"string"},
                  {"name":"age","type":["null","int"]}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        assertTrue(diff.isCompatible());
        assertTrue(diff.hasChanges());
        assertEquals(1, diff.changes(SchemaDiff.ChangeKind.ADDED).size());
    }

    @Test
    void isCompatibleFalseWhenFieldRemoved() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"name","type":"string"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        assertFalse(diff.isCompatible());
    }

    @Test
    void isCompatibleFalseWhenTypeChanged() {
        String next = """
                {"type":"record","name":"User","fields":[
                  {"name":"id","type":"double"},
                  {"name":"name","type":"string"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(BASE, next);
        assertFalse(diff.isCompatible());
    }

    @Test
    void changesAreOrderedByFieldName() {
        String old = """
                {"type":"record","name":"R","fields":[
                  {"name":"zebra","type":"string"}
                ]}""";
        String next = """
                {"type":"record","name":"R","fields":[
                  {"name":"apple","type":"string"},
                  {"name":"mango","type":"string"},
                  {"name":"zebra","type":"string"}
                ]}""";
        SchemaDiff diff = SchemaDiff.between(old, next);
        List<String> fields = diff.changes().stream().map(SchemaDiff.FieldChange::field).toList();
        assertEquals(List.of("apple", "mango"), fields);
    }

    @Test
    void emptyFieldsSchemasCompareAsCompatibleNoChange() {
        String empty = "{\"type\":\"record\",\"name\":\"E\",\"fields\":[]}";
        SchemaDiff diff = SchemaDiff.between(empty, empty);
        assertFalse(diff.hasChanges());
        assertEquals(0, diff.unchanged());
        assertTrue(diff.isCompatible());
    }

    @Test
    void addingToEmptySchemaIsAllAdded() {
        String empty = "{\"type\":\"record\",\"name\":\"E\",\"fields\":[]}";
        SchemaDiff diff = SchemaDiff.between(empty, BASE);
        assertEquals(2, diff.changes(SchemaDiff.ChangeKind.ADDED).size());
        assertEquals(0, diff.unchanged());
    }

    @Test
    void malformedJsonThrows() {
        String bad = "{\"type\":\"record\",\"fields\":[";
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.between(BASE, bad));
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.between(bad, BASE));
    }

    @Test
    void nonObjectSchemaThrows() {
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.between("[1,2,3]", BASE));
    }

    @Test
    void missingFieldsArrayThrows() {
        String noFields = "{\"type\":\"record\",\"name\":\"R\"}";
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.between(noFields, BASE));
    }

    @Test
    void nullArgumentThrows() {
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.between(null, BASE));
    }
}
