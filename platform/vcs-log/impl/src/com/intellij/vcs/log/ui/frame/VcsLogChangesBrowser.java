// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.frame;

import com.intellij.ide.ui.customization.CustomActionsSchema;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsActions;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vcs.changes.actions.diff.CombinedDiffPreview;
import com.intellij.openapi.vcs.changes.ui.*;
import com.intellij.openapi.vcs.changes.ui.browser.ChangesFilterer;
import com.intellij.openapi.vcs.changes.ui.browser.FilterableChangesBrowser;
import com.intellij.openapi.vcs.history.VcsDiffUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ClientProperty;
import com.intellij.ui.GuiUtils;
import com.intellij.ui.SideBorder;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.ui.switcher.QuickActionProvider;
import com.intellij.util.EventDispatcher;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.ui.StatusText;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.LoadingDetails;
import com.intellij.vcs.log.data.index.IndexedDetails;
import com.intellij.vcs.log.history.FileHistoryUtil;
import com.intellij.vcs.log.impl.MainVcsLogUiProperties;
import com.intellij.vcs.log.impl.MergedChange;
import com.intellij.vcs.log.impl.MergedChangeDiffRequestProvider;
import com.intellij.vcs.log.impl.VcsLogUiProperties;
import com.intellij.vcs.log.ui.VcsLogActionIds;
import com.intellij.vcs.log.util.VcsLogUiUtil;
import com.intellij.vcs.log.util.VcsLogUtil;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.util.*;
import java.util.function.Consumer;

import static com.intellij.diff.util.DiffUserDataKeysEx.*;
import static com.intellij.openapi.vcs.history.VcsDiffUtil.getRevisionTitle;
import static com.intellij.util.containers.ContainerUtil.getFirstItem;
import static com.intellij.vcs.log.impl.MainVcsLogUiProperties.SHOW_CHANGES_FROM_PARENTS;
import static com.intellij.vcs.log.impl.MainVcsLogUiProperties.SHOW_ONLY_AFFECTED_CHANGES;

/**
 * Change browser for commits in the Log. For merge commits, can display changes to the commits parents in separate groups.
 */
public final class VcsLogChangesBrowser extends FilterableChangesBrowser {
  public static final @NotNull DataKey<Boolean> HAS_AFFECTED_FILES = DataKey.create("VcsLogChangesBrowser.HasAffectedFiles");
  private final @NotNull MainVcsLogUiProperties myUiProperties;
  private final @NotNull Function<? super CommitId, ? extends VcsShortCommitDetails> myDataGetter;

  private final @NotNull VcsLogUiProperties.PropertiesChangeListener myListener;

  private final @NotNull Set<VirtualFile> myRoots = new HashSet<>();
  private boolean myHasMergeCommits = false;
  private final @NotNull List<Change> myChanges = new ArrayList<>();
  private final @NotNull Map<CommitId, Set<Change>> myChangesToParents = new LinkedHashMap<>();
  private @Nullable Collection<? extends FilePath> myAffectedPaths;
  private @NotNull Consumer<? super StatusText> myUpdateEmptyText = this::updateEmptyText;
  private final @NotNull Wrapper myToolbarWrapper;
  private final @NotNull EventDispatcher<Listener> myDispatcher = EventDispatcher.create(Listener.class);
  private @Nullable DiffPreviewController myEditorDiffPreviewController;

  VcsLogChangesBrowser(@NotNull Project project,
                       @NotNull MainVcsLogUiProperties uiProperties,
                       @NotNull Function<? super CommitId, ? extends VcsShortCommitDetails> getter,
                       boolean isWithEditorDiffPreview,
                       @NotNull Disposable parent) {
    super(project, false, false);
    myUiProperties = uiProperties;
    myDataGetter = getter;

    myListener = new VcsLogUiProperties.PropertiesChangeListener() {
      @Override
      public <T> void onPropertyChanged(@NotNull VcsLogUiProperties.VcsLogUiProperty<T> property) {
        if (SHOW_CHANGES_FROM_PARENTS.equals(property) || SHOW_ONLY_AFFECTED_CHANGES.equals(property)) {
          myViewer.rebuildTree();
        }
      }
    };
    myUiProperties.addChangeListener(myListener);

    Disposer.register(parent, this);

    JComponent toolbarComponent = getToolbar().getComponent();
    myToolbarWrapper = new Wrapper(toolbarComponent);
    GuiUtils.installVisibilityReferent(myToolbarWrapper, toolbarComponent);

    init();

    if (isWithEditorDiffPreview) {
      setEditorDiffPreview();
      EditorTabDiffPreviewManager.getInstance(myProject).subscribeToPreviewVisibilityChange(this, this::setEditorDiffPreview);
    }

    hideViewerBorder();
    myViewer.setEmptyText(VcsLogBundle.message("vcs.log.changes.select.commits.to.view.changes.status"));
    myViewer.rebuildTree();
  }

  @Override
  protected @NotNull JComponent createToolbarComponent() {
    return myToolbarWrapper;
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    JComponent centerPanel = super.createCenterPanel();
    JScrollPane scrollPane = UIUtil.findComponentOfType(centerPanel, JScrollPane.class);
    if (scrollPane != null) {
      ClientProperty.put(scrollPane, UIUtil.KEEP_BORDER_SIDES, SideBorder.TOP);
    }
    return centerPanel;
  }

  public void setToolbarHeightReferent(@NotNull JComponent referent) {
    myToolbarWrapper.setVerticalSizeReferent(referent);
  }

  public void addListener(@NotNull Listener listener, @NotNull Disposable disposable) {
    myDispatcher.addListener(listener, disposable);
  }

  @Override
  public void dispose() {
    super.dispose();
    myUiProperties.removeChangeListener(myListener);
  }

  @Override
  protected @NotNull List<AnAction> createToolbarActions() {
    return ContainerUtil.append(
      super.createToolbarActions(),
      CustomActionsSchema.getInstance().getCorrectedAction(VcsLogActionIds.CHANGES_BROWSER_TOOLBAR_ACTION_GROUP)
    );
  }

  @Override
  protected @NotNull List<AnAction> createPopupMenuActions() {
    return ContainerUtil.append(
      super.createPopupMenuActions(),
      ActionManager.getInstance().getAction(VcsLogActionIds.CHANGES_BROWSER_POPUP_ACTION_GROUP)
    );
  }

  private void updateModel(@NotNull Runnable update) {
    myChanges.clear();
    myChangesToParents.clear();
    myRoots.clear();
    myHasMergeCommits = false;
    myUpdateEmptyText = this::updateEmptyText;

    update.run();

    myUpdateEmptyText.accept(myViewer.getEmptyText());
    myViewer.rebuildTree();
    myDispatcher.getMulticaster().onModelUpdated();
  }

  public void resetSelectedDetails() {
    updateModel(() -> myUpdateEmptyText = text -> text.setText(""));
  }

  public void showText(@NotNull Consumer<? super StatusText> statusTextConsumer) {
    updateModel(() -> myUpdateEmptyText = statusTextConsumer);
  }

  @Override
  protected void onActiveChangesFilterChanges() {
    super.onActiveChangesFilterChanges();
    myUpdateEmptyText.accept(myViewer.getEmptyText());
  }

  public void setAffectedPaths(@Nullable Collection<? extends FilePath> paths) {
    myAffectedPaths = paths;
    myUpdateEmptyText.accept(myViewer.getEmptyText());
    myViewer.rebuildTree();
  }

  public void setSelectedDetails(@NotNull List<? extends VcsFullCommitDetails> detailsList) {
    updateModel(() -> {
      if (!detailsList.isEmpty()) {
        myRoots.addAll(ContainerUtil.map(detailsList, detail -> detail.getRoot()));
        myHasMergeCommits = ContainerUtil.exists(detailsList, detail -> detail.getParents().size() > 1);

        if (detailsList.size() == 1) {
          VcsFullCommitDetails detail = Objects.requireNonNull(getFirstItem(detailsList));
          myChanges.addAll(detail.getChanges());

          if (detail.getParents().size() > 1) {
            for (int i = 0; i < detail.getParents().size(); i++) {
              Set<Change> changesSet = new ReferenceOpenHashSet<>(detail.getChanges(i));
              myChangesToParents.put(new CommitId(detail.getParents().get(i), detail.getRoot()), changesSet);
            }
          }
        }
        else {
          myChanges.addAll(VcsLogUtil.collectChanges(detailsList, VcsFullCommitDetails::getChanges));
        }
      }
    });
  }

  private void updateEmptyText(@NotNull StatusText emptyText) {
    if (myRoots.isEmpty()) {
      emptyText.setText(VcsLogBundle.message("vcs.log.changes.select.commits.to.view.changes.status"));
    }
    else if (!myChangesToParents.isEmpty()) {
      emptyText.setText(VcsLogBundle.message("vcs.log.changes.no.merge.conflicts.status")).
        appendSecondaryText(VcsLogBundle.message("vcs.log.changes.show.changes.to.parents.status.action"),
                            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                            e -> myUiProperties.set(SHOW_CHANGES_FROM_PARENTS, true));
    }
    else if (isShowOnlyAffectedSelected() && myAffectedPaths != null) {
      emptyText.setText(VcsLogBundle.message("vcs.log.changes.no.changes.that.affect.selected.paths.status"))
        .appendSecondaryText(VcsLogBundle.message("vcs.log.changes.show.all.paths.status.action"),
                             SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                             e -> myUiProperties.set(SHOW_ONLY_AFFECTED_CHANGES, false));
    }
    else if (!myHasMergeCommits && hasActiveChangesFilter()) {
      emptyText.setText(VcsLogBundle.message("vcs.log.changes.no.changes.that.affect.selected.filters.status"))
        .appendSecondaryText(VcsLogBundle.message("vcs.log.changes.show.all.changes.status.action"),
                             SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                             e -> clearActiveChangesFilter());
    }
    else {
      emptyText.setText("");
    }
  }

  @Override
  protected @NotNull DefaultTreeModel buildTreeModel() {
    List<Change> changes = collectAffectedChanges(myChanges);
    ChangesFilterer.FilteredState filteredState = filterChanges(changes, !myHasMergeCommits);

    Map<CommitId, Collection<Change>> changesToParents = new LinkedHashMap<>();
    for (Map.Entry<CommitId, Set<Change>> entry : myChangesToParents.entrySet()) {
      changesToParents.put(entry.getKey(), collectAffectedChanges(entry.getValue()));
    }

    TreeModelBuilder builder = new TreeModelBuilder(myProject, getGrouping());
    setFilteredChanges(builder, filteredState, null);

    if (isShowChangesFromParents() && !changesToParents.isEmpty()) {
      if (changes.isEmpty()) {
        builder.createTagNode(VcsLogBundle.message("vcs.log.changes.no.merge.conflicts.node"));
      }
      for (CommitId commitId : changesToParents.keySet()) {
        Collection<Change> changesFromParent = changesToParents.get(commitId);
        if (!changesFromParent.isEmpty()) {
          ChangesBrowserNode<?> parentNode = new TagChangesBrowserNode(new ParentTag(commitId.getHash(), getText(commitId)),
                                                                       SimpleTextAttributes.REGULAR_ATTRIBUTES, false);
          parentNode.markAsHelperNode();

          builder.insertSubtreeRoot(parentNode);
          builder.insertChanges(changesFromParent, parentNode);
        }
      }
    }

    return builder.build();
  }

  private @NotNull List<Change> collectAffectedChanges(@NotNull Collection<? extends Change> changes) {
    if (!isShowOnlyAffectedSelected() || myAffectedPaths == null) return new ArrayList<>(changes);
    return ContainerUtil.filter(changes, change -> ContainerUtil.or(myAffectedPaths, filePath -> {
      if (filePath.isDirectory()) {
        return FileHistoryUtil.affectsDirectory(change, filePath);
      }
      else {
        return FileHistoryUtil.affectsFile(change, filePath, false) ||
               FileHistoryUtil.affectsFile(change, filePath, true);
      }
    }));
  }

  private boolean isShowChangesFromParents() {
    return myUiProperties.exists(SHOW_CHANGES_FROM_PARENTS) &&
           myUiProperties.get(SHOW_CHANGES_FROM_PARENTS);
  }

  private boolean isShowOnlyAffectedSelected() {
    return myUiProperties.exists(SHOW_ONLY_AFFECTED_CHANGES) &&
           myUiProperties.get(SHOW_ONLY_AFFECTED_CHANGES);
  }

  public @NotNull List<Change> getDirectChanges() {
    return myChanges;
  }

  public @NotNull List<Change> getSelectedChanges() {
    return VcsTreeModelData.selected(myViewer).userObjects(Change.class);
  }

  @Override
  public @Nullable Object getData(@NotNull String dataId) {
    if (HAS_AFFECTED_FILES.is(dataId)) {
      return myAffectedPaths != null;
    }
    if (PlatformCoreDataKeys.BGT_DATA_PROVIDER.is(dataId)) {
      Set<VirtualFile> roots = new HashSet<>(myRoots);
      VcsTreeModelData selectedData = VcsTreeModelData.selected(myViewer);
      DataProvider superProvider = (DataProvider)super.getData(dataId);
      return CompositeDataProvider.compose(slowId -> getSlowData(slowId, roots, selectedData), superProvider);
    }
    else if (QuickActionProvider.KEY.is(dataId)) {
      return new QuickActionProvider() {
        @Override
        public @NotNull List<AnAction> getActions(boolean originalProvider) {
          return SimpleToolWindowPanel.collectActions(VcsLogChangesBrowser.this);
        }

        @Override
        public JComponent getComponent() {
          return VcsLogChangesBrowser.this;
        }

        @Override
        public @NlsActions.ActionText @Nullable String getName() {
          return null;
        }
      };
    }
    return super.getData(dataId);
  }

  private @Nullable Object getSlowData(@NotNull String dataId, @NotNull Set<? extends VirtualFile> roots, @NotNull VcsTreeModelData selectedData) {
    if (VcsDataKeys.VCS.is(dataId)) {
      AbstractVcs rootsVcs = JBIterable.from(roots)
        .map(root -> ProjectLevelVcsManager.getInstance(myProject).getVcsFor(root))
        .filterNotNull()
        .unique()
        .single();
      if (rootsVcs != null) return rootsVcs.getKeyInstanceMethod();

      AbstractVcs selectionVcs = selectedData.iterateUserObjects(Change.class)
        .map(change -> ChangesUtil.getFilePath(change))
        .map(root -> ProjectLevelVcsManager.getInstance(myProject).getVcsFor(root))
        .filterNotNull()
        .unique()
        .single();
      if (selectionVcs != null) return selectionVcs.getKeyInstanceMethod();

      return null;
    }
    return null;
  }

  @Override
  public ChangeDiffRequestChain.Producer getDiffRequestProducer(@NotNull Object userObject) {
    return getDiffRequestProducer(userObject, false);
  }

  public @Nullable ChangeDiffRequestChain.Producer getDiffRequestProducer(@NotNull Object userObject, boolean forDiffPreview) {
    if (!(userObject instanceof Change)) return null;
    Change change = (Change)userObject;

    Map<Key<?>, Object> context = new HashMap<>();
    if (!(change instanceof MergedChange)) {
      putRootTagIntoChangeContext(change, context);
    }
    return createDiffRequestProducer(myProject, change, context, forDiffPreview);
  }

  public static @Nullable ChangeDiffRequestChain.Producer createDiffRequestProducer(@NotNull Project project,
                                                                                    @NotNull Change change,
                                                                                    @NotNull Map<Key<?>, Object> context,
                                                                                    boolean forDiffPreview) {
    if (change instanceof MergedChange) {
      MergedChange mergedChange = (MergedChange)change;
      if (mergedChange.getSourceChanges().size() == 2) {
        if (forDiffPreview) {
          putFilePathsIntoMergedChangeContext(mergedChange, context);
        }
        return new MergedChangeDiffRequestProvider.MyProducer(project, mergedChange, context);
      }
    }

    if (forDiffPreview) {
      VcsDiffUtil.putFilePathsIntoChangeContext(change, context);
    }

    return ChangeDiffRequestProducer.create(project, change, context);
  }

  private void setEditorDiffPreview() {

    DiffPreviewController editorDiffPreviewController = myEditorDiffPreviewController;

    boolean isWithEditorDiffPreview = VcsLogUiUtil.isDiffPreviewInEditor(myProject);

    if (isWithEditorDiffPreview && editorDiffPreviewController == null) {
      myEditorDiffPreviewController = new VcsLogChangesBrowserDiffPreviewController();
    }
    else if (!isWithEditorDiffPreview && editorDiffPreviewController != null) {
      editorDiffPreviewController.getActivePreview().closePreview();
      myEditorDiffPreviewController = null;
    }
  }

  public @NotNull VcsLogChangeProcessor createChangeProcessor(boolean isInEditor) {
    return new VcsLogChangeProcessor(myProject, this, isInEditor, this);
  }

  @Override
  protected @Nullable DiffPreview getShowDiffActionPreview() {
    DiffPreviewController editorDiffPreviewController = myEditorDiffPreviewController;
    return editorDiffPreviewController != null ? editorDiffPreviewController.getActivePreview() : null;
  }

  public void selectChange(@NotNull Object userObject, @Nullable ChangesBrowserNode.Tag tag) {
    selectObjectWithTag(myViewer, userObject, tag);
  }

  public @Nullable ChangesBrowserNode.Tag getTag(@NotNull Change change) {
    CommitId parentId = null;
    for (CommitId commitId : myChangesToParents.keySet()) {
      if (myChangesToParents.get(commitId).contains(change)) {
        parentId = commitId;
        break;
      }
    }

    if (parentId == null) return null;
    return new ParentTag(parentId.getHash(), getText(parentId));
  }

  private void putRootTagIntoChangeContext(@NotNull Change change, @NotNull Map<Key<?>, Object> context) {
    ChangesBrowserNode.Tag tag = getTag(change);
    if (tag != null) {
      context.put(ChangeDiffRequestProducer.TAG_KEY, tag);
    }
  }

  private static void putFilePathsIntoMergedChangeContext(@NotNull MergedChange change, @NotNull Map<Key<?>, Object> context) {
    ContentRevision centerRevision = change.getAfterRevision();
    ContentRevision leftRevision = change.getSourceChanges().get(0).getBeforeRevision();
    ContentRevision rightRevision = change.getSourceChanges().get(1).getBeforeRevision();
    FilePath centerFile = centerRevision == null ? null : centerRevision.getFile();
    FilePath leftFile = leftRevision == null ? null : leftRevision.getFile();
    FilePath rightFile = rightRevision == null ? null : rightRevision.getFile();
    context.put(VCS_DIFF_CENTER_CONTENT_TITLE, getRevisionTitle(centerRevision, centerFile, null));
    context.put(VCS_DIFF_RIGHT_CONTENT_TITLE, getRevisionTitle(rightRevision, rightFile, centerFile));
    context.put(VCS_DIFF_LEFT_CONTENT_TITLE, getRevisionTitle(leftRevision, leftFile, centerFile == null ? rightFile : centerFile));
  }

  private @NotNull @Nls String getText(@NotNull CommitId commitId) {
    String text = VcsLogBundle.message("vcs.log.changes.changes.to.parent.node", commitId.getHash().toShortString());
    VcsShortCommitDetails detail = myDataGetter.fun(commitId);
    if (!(detail instanceof LoadingDetails) || (detail instanceof IndexedDetails)) {
      text += " " + StringUtil.shortenTextWithEllipsis(detail.getSubject(), 50, 0);
    }
    return text;
  }

  public interface Listener extends EventListener {
    void onModelUpdated();
  }

  private class VcsLogChangesBrowserDiffPreviewController extends DiffPreviewControllerBase {
    @Override
    protected @NotNull DiffPreview getSimplePreview() {
      return new VcsLogEditorDiffPreview(myProject, VcsLogChangesBrowser.this);
    }

    @Override
    protected @NotNull CombinedDiffPreview createCombinedDiffPreview() {
      return new VcsLogCombinedDiffPreview(VcsLogChangesBrowser.this);
    }
  }

  private static class ParentTag extends ChangesBrowserNode.ValueTag<Hash> {
    private final @NotNull @Nls String myText;

    ParentTag(@NotNull Hash commit, @NotNull @Nls String text) {
      super(commit);
      myText = text;
    }

    @Override
    public String toString() {
      return myText;
    }
  }
}
