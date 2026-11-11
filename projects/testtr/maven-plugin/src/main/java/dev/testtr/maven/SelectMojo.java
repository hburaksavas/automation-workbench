package dev.testtr.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Run before the test lifecycle: testtr:select test. */
@Mojo(name = "select", threadSafe = false)
public final class SelectMojo extends ImpactMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Override public void execute() throws MojoExecutionException {
        if (session.getProjects().size() != 1)
            throw new MojoExecutionException("testtr:select tek test modülünde çalıştırılmalıdır; -f ile modül POM'unu seçin, -am kullanmayın.");
        var project = session.getCurrentProject();
        try {
            ImpactSelection.check(session.getUserProperties(), project.getProperties());
            var result = analyzeAndWrite();
            ImpactSelection.apply(result, session.getUserProperties(), project.getProperties());
            getLog().info(result.selected().isEmpty()
                    ? "Seçilen senaryo yok; bu Maven oturumunda testler atlanacak."
                    : "Surefire için " + result.selected().size() + " TestTR senaryosu seçildi. Devam etmek için aynı komutta test hedefini kullanın.");
        } catch (IllegalArgumentException exception) {
            throw new MojoExecutionException(exception.getMessage(), exception);
        }
    }
}
