package io.jenkins.plugins.octanesuitegatebyembiti.listeners;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneProgressEmailScheduler;

@Extension
public class OctaneProgressEmailRunListener extends RunListener<Run<?, ?>> {
  @Override
  public void onCompleted(Run<?, ?> run, TaskListener listener) {
    OctaneProgressEmailScheduler.get().cancelOwner(run.getExternalizableId());
  }

  @Override
  public void onDeleted(Run<?, ?> run) {
    OctaneProgressEmailScheduler.get().cancelOwner(run.getExternalizableId());
  }
}
