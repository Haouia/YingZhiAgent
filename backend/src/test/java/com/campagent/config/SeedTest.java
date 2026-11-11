package com.campagent.config;

import com.campagent.common.Db;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SeedTest {
    @Test
    void weakDemoPasswordRequiresLocalDemo() {
        Db db = mock(Db.class);
        var cloud = new Seed(db, null, null, "123456", "cloud", "127.0.0.1");
        var publicDemo = new Seed(db, null, null, "123456", "demo", "0.0.0.0");

        assertThrows(IllegalStateException.class, () -> cloud.run(null));
        assertThrows(IllegalStateException.class, () -> publicDemo.run(null));
        verifyNoInteractions(db);
    }
}
