package org.jbpm.test;

import static org.jbpm.test.JBPMHelper.createEnvironment;
import static org.jbpm.test.JBPMHelper.txStateName;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;

import junit.framework.Assert;

import org.drools.core.ClockType;
import org.drools.core.SessionConfiguration;
import org.drools.core.audit.WorkingMemoryInMemoryLogger;
import org.drools.core.audit.event.LogEvent;
import org.drools.core.audit.event.RuleFlowNodeLogEvent;
import org.drools.core.impl.EnvironmentFactory;
import org.h2.tools.DeleteDbFiles;
import org.h2.tools.Server;
import org.jbpm.process.audit.AuditLoggerFactory;
import org.jbpm.process.audit.AuditLoggerFactory.Type;
import org.jbpm.process.audit.JPAProcessInstanceDbLog;
import org.jbpm.process.audit.NodeInstanceLog;
import org.jbpm.process.instance.event.DefaultSignalManagerFactory;
import org.jbpm.process.instance.impl.DefaultProcessInstanceManagerFactory;
import org.jbpm.shared.services.api.JbpmServicesTransactionManager;
import org.jbpm.shared.services.impl.JbpmJTATransactionManager;
import org.jbpm.task.HumanTaskServiceFactory;
import org.jbpm.task.wih.HTWorkItemHandlerFactory;
import org.jbpm.task.wih.LocalHTWorkItemHandler;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.kie.api.definition.process.Node;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.process.NodeInstance;
import org.kie.api.runtime.process.NodeInstanceContainer;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.kie.internal.KnowledgeBase;
import org.kie.internal.KnowledgeBaseFactory;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderError;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;
import org.kie.internal.persistence.jpa.JPAKnowledgeService;
import org.kie.internal.runtime.StatefulKnowledgeSession;
import org.kie.internal.task.api.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.jdbc.PoolingDataSource;

/**
 * Base test case for the jbpm-bpmn2 module.
 *
 * Please keep this test class in the org.jbpm.bpmn2 package or otherwise give
 * it a unique name.
 *
 */
public abstract class JbpmJUnitTestCase extends Assert {

    protected final static String EOL = System.getProperty("line.separator");
    private boolean setupDataSource = false;
    private boolean sessionPersistence = false;
    private EntityManagerFactory emf;
    private PoolingDataSource ds;
    private H2Server server = new H2Server();
    private TaskService taskService;
    private TestWorkItemHandler workItemHandler = new TestWorkItemHandler();
    private WorkingMemoryInMemoryLogger logger;
    private JPAProcessInstanceDbLog log;
    private Logger testLogger = null;

    @Rule
    public KnowledgeSessionCleanup ksessionCleanupRule = new KnowledgeSessionCleanup();	
	protected static ThreadLocal<Set<StatefulKnowledgeSession>> knowledgeSessionSetLocal 
	    = KnowledgeSessionCleanup.knowledgeSessionSetLocal;
    @Rule
    public TestName testName = new TestName();
    
    public JbpmJUnitTestCase() {
        this(false);
    }

    public JbpmJUnitTestCase(boolean setupDataSource) {
        System.setProperty("jbpm.user.group.mapping", "classpath:/usergroups.properties");
        System.setProperty("jbpm.usergroup.callback", "org.jbpm.task.identity.DefaultUserGroupCallbackImpl");
        this.setupDataSource = setupDataSource;
    }

    public static PoolingDataSource setupPoolingDataSource() {
        PoolingDataSource pds = new PoolingDataSource();
        pds.setUniqueName("jdbc/jbpm-ds");
        pds.setClassName("bitronix.tm.resource.jdbc.lrc.LrcXADataSource");
        pds.setMaxPoolSize(5);
        pds.setAllowLocalTransactions(true);
        pds.getDriverProperties().put("user", "sa");
        pds.getDriverProperties().put("password", "");
        pds.getDriverProperties().put("url", "jdbc:h2:tcp://localhost/~/jbpm-db");
        pds.getDriverProperties().put("driverClassName", "org.h2.Driver");
        pds.init();
        return pds;
    }

    public void setPersistence(boolean sessionPersistence) {
        this.sessionPersistence = sessionPersistence;
    }

    public boolean isPersistence() {
        return sessionPersistence;
    }

    @Before
    public void setUp() throws Exception {
        if (testLogger == null) {
            testLogger = LoggerFactory.getLogger(getClass());
        }
        if (setupDataSource) {
            server.start();
            ds = setupPoolingDataSource();
            emf = Persistence.createEntityManagerFactory("org.jbpm.persistence.jpa");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (setupDataSource) {
            taskService = null;
            if (emf != null) {
                emf.close();
                emf = null;
            }
            if (ds != null) {
                ds.close();
                ds = null;
            }
            server.stop();
            DeleteDbFiles.execute("~", "jbpm-db", true);

            // Clean up possible transactions
            Transaction tx = TransactionManagerServices.getTransactionManager().getCurrentTransaction();
            if (tx != null) {
                int testTxState = tx.getStatus();
                if (testTxState != Status.STATUS_NO_TRANSACTION
                        && testTxState != Status.STATUS_ROLLEDBACK
                        && testTxState != Status.STATUS_COMMITTED) {
                    try {
                        tx.rollback();
                    } catch (Throwable t) {
                        // do nothing..
                    }
                    Assert.fail("Transaction had status " + txStateName[testTxState] + " at the end of the test.");
                }
            }
        }
    }

    protected KnowledgeBase createKnowledgeBase(String... process) {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        for (String p : process) {
            kbuilder.add(ResourceFactory.newClassPathResource(p), ResourceType.BPMN2);
        }

        // Check for errors
        if (kbuilder.hasErrors()) {
            if (kbuilder.getErrors().size() > 0) {
                boolean errors = false;
                for (KnowledgeBuilderError error : kbuilder.getErrors()) {
                    testLogger.error(error.toString());
                    errors = true;
                }
                assertFalse("Could not build knowldge base.", errors);
            }
        }
        return kbuilder.newKnowledgeBase();
    }

    protected KnowledgeBase createKnowledgeBase(Map<String, ResourceType> resources) throws Exception {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        for (Map.Entry<String, ResourceType> entry : resources.entrySet()) {
            kbuilder.add(ResourceFactory.newClassPathResource(entry.getKey()), entry.getValue());
        }
        return kbuilder.newKnowledgeBase();
    }

    protected KnowledgeBase createKnowledgeBaseGuvnor(String... packages) throws Exception {
        return createKnowledgeBaseGuvnor(false, "http://localhost:8080/drools-guvnor", "admin", "admin", packages);
    }

    protected KnowledgeBase createKnowledgeBaseGuvnorAssets(String pkg, String... assets) throws Exception {
        return createKnowledgeBaseGuvnor(false, "http://localhost:8080/drools-guvnor", "admin", "admin", pkg, assets);
    }

    protected KnowledgeBase createKnowledgeBaseGuvnor(boolean dynamic, String url, String username,
            String password, String pkg, String... assets) throws Exception {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        String changeSet =
                "<change-set xmlns='http://drools.org/drools-5.0/change-set'" + EOL
                + "            xmlns:xs='http://www.w3.org/2001/XMLSchema-instance'" + EOL
                + "            xs:schemaLocation='http://drools.org/drools-5.0/change-set http://anonsvn.jboss.org/repos/labs/labs/jbossrules/trunk/drools-api/src/main/resources/change-set-1.0.0.xsd' >" + EOL
                + "    <add>" + EOL;
        for (String a : assets) {
            if (a.indexOf(".bpmn") >= 0) {
                a = a.substring(0, a.indexOf(".bpmn"));
            }
            changeSet += "        <resource source='" + url + "/rest/packages/" + pkg + "/assets/" + a + "/binary' type='BPMN2' basicAuthentication=\"enabled\" username=\"" + username + "\" password=\"" + password + "\" />" + EOL;
        }
        changeSet +=
                "    </add>" + EOL
                + "</change-set>";
        kbuilder.add(ResourceFactory.newByteArrayResource(changeSet.getBytes()), ResourceType.CHANGE_SET);
        return kbuilder.newKnowledgeBase();
    }

    protected KnowledgeBase createKnowledgeBaseGuvnor(boolean dynamic, String url, String username,
            String password, String... packages) throws Exception {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        String changeSet =
                "<change-set xmlns='http://drools.org/drools-5.0/change-set'" + EOL
                + "            xmlns:xs='http://www.w3.org/2001/XMLSchema-instance'" + EOL
                + "            xs:schemaLocation='http://drools.org/drools-5.0/change-set http://anonsvn.jboss.org/repos/labs/labs/jbossrules/trunk/drools-api/src/main/resources/change-set-1.0.0.xsd' >" + EOL
                + "    <add>" + EOL;
        for (String p : packages) {
            changeSet += "        <resource source='" + url + "/rest/packages/" + p + "/binary' type='PKG' basicAuthentication=\"enabled\" username=\"" + username + "\" password=\"" + password + "\" />" + EOL;
        }
        changeSet +=
                "    </add>" + EOL
                + "</change-set>";
        kbuilder.add(ResourceFactory.newByteArrayResource(changeSet.getBytes()), ResourceType.CHANGE_SET);
        return kbuilder.newKnowledgeBase();
    }

    protected StatefulKnowledgeSession createKnowledgeSession(KnowledgeBase kbase) {
        StatefulKnowledgeSession result;
        KieSessionConfiguration conf = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        // Do NOT use the Pseudo clock yet.. 
        // conf.setOption( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );

        if (sessionPersistence) {
            Environment env = createEnvironment(emf);
            result = JPAKnowledgeService.newStatefulKnowledgeSession(kbase, conf, env);
            AuditLoggerFactory.newInstance(Type.JPA, result, null);
            if (log == null) {
                log = new JPAProcessInstanceDbLog(result.getEnvironment());
            }
        } else {
            Environment env = EnvironmentFactory.newEnvironment();
            env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, emf);

            Properties defaultProps = new Properties();
            defaultProps.setProperty("drools.processSignalManagerFactory", DefaultSignalManagerFactory.class.getName());
            defaultProps.setProperty("drools.processInstanceManagerFactory", DefaultProcessInstanceManagerFactory.class.getName());
            conf = new SessionConfiguration(defaultProps);

            result = kbase.newStatefulKnowledgeSession(conf, env);
            logger = new WorkingMemoryInMemoryLogger(result);
        }
        //knowledgeSessionSetLocal.get().add(result);
        return result;
    }

    protected StatefulKnowledgeSession createKnowledgeSession(String... process) {
        KnowledgeBase kbase = createKnowledgeBase(process);
        return createKnowledgeSession(kbase);
    }

    protected StatefulKnowledgeSession restoreSession(StatefulKnowledgeSession ksession, boolean noCache) throws SystemException {
        if (sessionPersistence) {
            int id = ksession.getId();
            KnowledgeBase kbase = ksession.getKieBase();
            Transaction tx = TransactionManagerServices.getTransactionManager().getCurrentTransaction();
            if (tx != null) {
                int txStatus = tx.getStatus();
                assertTrue("Current transaction state is " + txStateName[txStatus], tx.getStatus() == Status.STATUS_NO_TRANSACTION);
            }
            Environment env = null;
            if (noCache) {
                emf.close();
                env = EnvironmentFactory.newEnvironment();
                emf = Persistence.createEntityManagerFactory("org.jbpm.persistence.jpa");
                env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, emf);
                env.set(EnvironmentName.TRANSACTION_MANAGER, TransactionManagerServices.getTransactionManager());
                JPAProcessInstanceDbLog.setEnvironment(env);
                taskService = null;
            } else {
                env = ksession.getEnvironment();
                taskService = null;
            }
            KieSessionConfiguration config = ksession.getSessionConfiguration();
            ksession.dispose();

            // reload knowledge session 
            ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(id, kbase, config, env);
            KnowledgeSessionCleanup.knowledgeSessionSetLocal.get().add(ksession);
            AuditLoggerFactory.newInstance(Type.JPA, ksession, null);
            return ksession;
        } else {
            return ksession;
        }
    }

    public StatefulKnowledgeSession loadSession(int id, String... process) {
        KnowledgeBase kbase = createKnowledgeBase(process);

        final KieSessionConfiguration config = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        config.setOption(ClockTypeOption.get(ClockType.PSEUDO_CLOCK.getId()));

        StatefulKnowledgeSession ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(id, kbase, config, createEnvironment(emf));
        KnowledgeSessionCleanup.knowledgeSessionSetLocal.get().add(ksession);
        AuditLoggerFactory.newInstance(Type.JPA, ksession, null);

        return ksession;
    }

    public Object getVariableValue(String name, long processInstanceId, StatefulKnowledgeSession ksession) {
        return ((WorkflowProcessInstance) ksession.getProcessInstance(processInstanceId)).getVariable(name);
    }

    public void assertProcessInstanceCompleted(long processInstanceId, StatefulKnowledgeSession ksession) {
        assertNull(ksession.getProcessInstance(processInstanceId));
    }

    public void assertProcessInstanceAborted(long processInstanceId, StatefulKnowledgeSession ksession) {
        assertNull(ksession.getProcessInstance(processInstanceId));
    }

    public void assertProcessInstanceActive(long processInstanceId, StatefulKnowledgeSession ksession) {
        assertNotNull(ksession.getProcessInstance(processInstanceId));
    }

    public void assertNodeActive(long processInstanceId, StatefulKnowledgeSession ksession, String... name) {
        List<String> names = new ArrayList<String>();
        for (String n : name) {
            names.add(n);
        }
        ProcessInstance processInstance = ksession.getProcessInstance(processInstanceId);
        if (processInstance instanceof WorkflowProcessInstance) {
            assertNodeActive((WorkflowProcessInstance) processInstance, names);
        }
        if (!names.isEmpty()) {
            String s = names.get(0);
            for (int i = 1; i < names.size(); i++) {
                s += ", " + names.get(i);
            }
            fail("Node(s) not active: " + s);
        }
    }

    private void assertNodeActive(NodeInstanceContainer container, List<String> names) {
        for (NodeInstance nodeInstance : container.getNodeInstances()) {
            String nodeName = nodeInstance.getNodeName();
            if (names.contains(nodeName)) {
                names.remove(nodeName);
            }
            if (nodeInstance instanceof NodeInstanceContainer) {
                assertNodeActive((NodeInstanceContainer) nodeInstance, names);
            }
        }
    }

    public void assertNodeTriggered(long processInstanceId, String... nodeNames) {
        List<String> names = new ArrayList<String>();
        for (String nodeName : nodeNames) {
            names.add(nodeName);
        }
        if (sessionPersistence) {
            List<NodeInstanceLog> logs = log.findNodeInstances(processInstanceId);
            if (logs != null) {
                for (NodeInstanceLog l : logs) {
                    String nodeName = l.getNodeName();
                    if ((l.getType() == NodeInstanceLog.TYPE_ENTER || l.getType() == NodeInstanceLog.TYPE_EXIT) && names.contains(nodeName)) {
                        names.remove(nodeName);
                    }
                }
            }
        } else {
            for (LogEvent event : logger.getLogEvents()) {
                if (event instanceof RuleFlowNodeLogEvent) {
                    String nodeName = ((RuleFlowNodeLogEvent) event).getNodeName();
                    if (names.contains(nodeName)) {
                        names.remove(nodeName);
                    }
                }
            }
        }
        if (!names.isEmpty()) {
            String s = names.get(0);
            for (int i = 1; i < names.size(); i++) {
                s += ", " + names.get(i);
            }
            fail("Node(s) not executed: " + s);
        }
    }

    protected void clearHistory() {
        if (sessionPersistence) {
            if (log == null) {
                log = new JPAProcessInstanceDbLog();
            }
            log.clear();
        } else {
            logger.clear();
        }
    }

    public TestWorkItemHandler getTestWorkItemHandler() {
        return workItemHandler;
    }

    public static class TestWorkItemHandler implements WorkItemHandler {

        private List<WorkItem> workItems = new ArrayList<WorkItem>();

        public void executeWorkItem(WorkItem workItem, WorkItemManager manager) {
            workItems.add(workItem);
        }

        public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
        }

        public WorkItem getWorkItem() {
            if (workItems.size() == 0) {
                return null;
            }
            if (workItems.size() == 1) {
                WorkItem result = workItems.get(0);
                this.workItems.clear();
                return result;
            } else {
                throw new IllegalArgumentException("More than one work item active");
            }
        }

        public List<WorkItem> getWorkItems() {
            List<WorkItem> result = new ArrayList<WorkItem>(workItems);
            workItems.clear();
            return result;
        }
    }

    public void assertProcessVarExists(ProcessInstance process, String... processVarNames) {
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        List<String> names = new ArrayList<String>();
        for (String nodeName : processVarNames) {
            names.add(nodeName);
        }

        for (String pvar : instance.getVariables().keySet()) {
            if (names.contains(pvar)) {
                names.remove(pvar);
            }
        }

        if (!names.isEmpty()) {
            String s = names.get(0);
            for (int i = 1; i < names.size(); i++) {
                s += ", " + names.get(i);
            }
            fail("Process Variable(s) do not exist: " + s);
        }

    }

    public void assertNodeExists(ProcessInstance process, String... nodeNames) {
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        List<String> names = new ArrayList<String>();
        for (String nodeName : nodeNames) {
            names.add(nodeName);
        }

        for (Node node : instance.getNodeContainer().getNodes()) {
            if (names.contains(node.getName())) {
                names.remove(node.getName());
            }
        }

        if (!names.isEmpty()) {
            String s = names.get(0);
            for (int i = 1; i < names.size(); i++) {
                s += ", " + names.get(i);
            }
            fail("Node(s) do not exist: " + s);
        }
    }

    public void assertNumOfIncommingConnections(ProcessInstance process, String nodeName, int num) {
        assertNodeExists(process, nodeName);
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        for (Node node : instance.getNodeContainer().getNodes()) {
            if (node.getName().equals(nodeName)) {
                if (node.getIncomingConnections().size() != num) {
                    fail("Expected incomming connections: " + num + " - found " + node.getIncomingConnections().size());
                } else {
                    break;
                }
            }
        }
    }

    public void assertNumOfOutgoingConnections(ProcessInstance process, String nodeName, int num) {
        assertNodeExists(process, nodeName);
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        for (Node node : instance.getNodeContainer().getNodes()) {
            if (node.getName().equals(nodeName)) {
                if (node.getOutgoingConnections().size() != num) {
                    fail("Expected outgoing connections: " + num + " - found " + node.getOutgoingConnections().size());
                } else {
                    break;
                }
            }
        }
    }

    public void assertVersionEquals(ProcessInstance process, String version) {
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        if (!instance.getWorkflowProcess().getVersion().equals(version)) {
            fail("Expected version: " + version + " - found " + instance.getWorkflowProcess().getVersion());
        }
    }

    public void assertProcessNameEquals(ProcessInstance process, String name) {
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        if (!instance.getWorkflowProcess().getName().equals(name)) {
            fail("Expected name: " + name + " - found " + instance.getWorkflowProcess().getName());
        }
    }

    public void assertPackageNameEquals(ProcessInstance process, String packageName) {
        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) process;
        if (!instance.getWorkflowProcess().getPackageName().equals(packageName)) {
            fail("Expected package name: " + packageName + " - found " + instance.getWorkflowProcess().getPackageName());
        }
    }

    public TaskService getTaskService(StatefulKnowledgeSession ksession) {
       
        if (taskService == null) {
            JbpmServicesTransactionManager txManager = new JbpmJTATransactionManager();
            HumanTaskServiceFactory.setEntityManagerFactory(emf);
            HumanTaskServiceFactory.setJbpmServicesTransactionManager(txManager);
            taskService = HumanTaskServiceFactory.newTaskService();

        }
        
        LocalHTWorkItemHandler humanTaskHandler =  HTWorkItemHandlerFactory.newHandler(ksession, taskService);
        
        ksession.getWorkItemManager().registerWorkItemHandler("Human Task", humanTaskHandler);
        
        return taskService;
    }

    public TaskService getService() {
        return HumanTaskServiceFactory.newTaskService();
    }
    private static class H2Server {

        private Server server;

        public synchronized void start() {
            if (server == null || !server.isRunning(false)) {
                try {
                    DeleteDbFiles.execute("~", "jbpm-db", true);
                    server = Server.createTcpServer(new String[0]);
                    server.start();
                } catch (SQLException e) {
                    throw new RuntimeException("Cannot start h2 server database", e);
                }
            }
        }

        public synchronized void finalize() throws Throwable {
            stop();
            super.finalize();
        }

        public void stop() {
            if (server != null) {
                server.stop();
                server.shutdown();
                DeleteDbFiles.execute("~", "jbpm-db", true);
                server = null;
            }
        }
    }

    public PoolingDataSource getDs() {
        return ds;
    }

    public EntityManagerFactory getEmf() {
        return emf;
    }
    
    
}
