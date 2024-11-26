package org.netpreserve.warcaroo;

import org.junit.jupiter.api.extension.*;

import java.io.IOException;

public class InMemoryDatabaseTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static Database sharedDatabase;

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        if (sharedDatabase == null) {
            sharedDatabase = Database.newDatabaseInMemory();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (sharedDatabase != null && !isSharedWithOtherContexts(context)) {
            sharedDatabase.close();
            sharedDatabase = null;
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Database.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return sharedDatabase;
    }

    private boolean isSharedWithOtherContexts(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                       .get(Database.class.getName()) != null;
    }
}