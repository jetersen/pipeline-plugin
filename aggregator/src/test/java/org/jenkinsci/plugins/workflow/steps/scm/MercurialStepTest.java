/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.steps.scm;

import hudson.plugins.mercurial.MercurialSCM;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.triggers.SCMTrigger;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import jenkins.util.VirtualFile;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RandomlyFails;

@For(GenericSCMStep.class) // formerly a dedicated MercurialStep
public class MercurialStepTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void hg(File repo, String... cmds) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("hg");
        args.addAll(Arrays.asList(cmds));
        SubversionStepTest.run(repo, args.toArray(new String[args.size()]));
    }

    /** Otherwise {@link JenkinsRule#waitUntilNoActivity()} is ineffective when we have just pinged a commit notification endpoint. */
    @Before public void synchronousPolling() {
        r.jenkins.getDescriptorByType(SCMTrigger.DescriptorImpl.class).synchronousPolling = true;
    }

    @RandomlyFails("TODO sometimes get expected:<2> but was:<3> (probably need to suppress builds during notifyCommit)")
    @Test public void multipleSCMs() throws Exception {
        File sampleRepo = tmp.newFolder();
        hg(sampleRepo, "init");
        FileUtils.touch(new File(sampleRepo, "file"));
        hg(sampleRepo, "add", "file");
        hg(sampleRepo, "commit", "--message=init");
        URI sampleRepoU = sampleRepo.toURI();
        File otherRepo = tmp.newFolder();
        hg(otherRepo, "init");
        FileUtils.touch(new File(otherRepo, "otherfile"));
        hg(otherRepo, "add", "otherfile");
        hg(otherRepo, "commit", "--message=init");
        URI otherRepoU = otherRepo.toURI();
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "demo");
        p.addTrigger(new SCMTrigger(""));
        p.setQuietPeriod(3); // so it only does one build
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
            "    ws {\n" +
            "        dir('main') {\n" +
            "            checkout([$class: 'MercurialSCM', source: $/" + sampleRepoU + "/$])\n" +
            "        }\n" +
            "        dir('other') {\n" +
            "            checkout([$class: 'MercurialSCM', source: $/" + otherRepoU + "/$, clean: true])\n" +
            "            try {\n" +
            "                readFile 'unversioned'\n" +
            "                error 'unversioned did exist'\n" +
            "            } catch (FileNotFoundException x) {\n" +
            "                echo 'unversioned did not exist'\n" +
            "            }\n" +
            "            writeFile text: '', file: 'unversioned'\n" +
            "        }\n" +
            "        archive '**'\n" +
            "    }\n" +
            "}"));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        VirtualFile artifacts = b.getArtifactManager().root();
        assertTrue(artifacts.child("main/file").isFile());
        assertTrue(artifacts.child("other/otherfile").isFile());
        r.assertLogContains("unversioned did not exist", b);
        FileUtils.touch(new File(sampleRepo, "file2"));
        hg(sampleRepo, "add", "file2");
        hg(sampleRepo, "commit", "--message=file2");
        FileUtils.touch(new File(otherRepo, "otherfile2"));
        hg(otherRepo, "add", "otherfile2");
        hg(otherRepo, "commit", "--message=otherfile2");
        System.out.println(r.createWebClient().goTo("mercurial/notifyCommit?url=" + URLEncoder.encode(sampleRepoU.toString(), "UTF-8"), "text/plain").getWebResponse().getContentAsString());
        System.out.println(r.createWebClient().goTo("mercurial/notifyCommit?url=" + URLEncoder.encode(otherRepoU.toString(), "UTF-8"), "text/plain").getWebResponse().getContentAsString());
        r.waitUntilNoActivity();
        FileUtils.copyFile(p.getSCMTrigger().getLogFile(), System.out);
        b = r.assertBuildStatusSuccess(p.getLastBuild());
        assertEquals(2, b.number);
        artifacts = b.getArtifactManager().root();
        assertTrue(artifacts.child("main/file2").isFile());
        assertTrue(artifacts.child("other/otherfile2").isFile());
        r.assertLogContains("unversioned did not exist", b);
        Iterator<? extends SCM> scms = p.getSCMs().iterator();
        assertTrue(scms.hasNext());
        assertEquals(sampleRepoU.toString(), ((MercurialSCM) scms.next()).getSource());
        assertTrue(scms.hasNext());
        assertEquals(otherRepoU.toString(), ((MercurialSCM) scms.next()).getSource());
        assertFalse(scms.hasNext());
        List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
        assertEquals(2, changeSets.size());
        ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = changeSets.get(0);
        assertEquals(b, changeSet.getRun());
        assertEquals("hg", changeSet.getKind());
        Iterator<? extends ChangeLogSet.Entry> iterator = changeSet.iterator();
        assertTrue(iterator.hasNext());
        ChangeLogSet.Entry entry = iterator.next();
        assertEquals("[file2]", entry.getAffectedPaths().toString());
        assertFalse(iterator.hasNext());
        changeSet = changeSets.get(1);
        iterator = changeSet.iterator();
        assertTrue(iterator.hasNext());
        entry = iterator.next();
        assertEquals("[otherfile2]", entry.getAffectedPaths().toString());
        assertFalse(iterator.hasNext());
    }

}
