package editor.shipit;

import editor.FileTree;
import editor.FileTreeUtil;
import editor.GosuPanel;
import editor.LabFrame;
import editor.NodeKind;
import editor.MessageTree;
import editor.MessagesPanel;
import editor.debugger.Debugger;
import editor.search.IncrementalCompilerUsageSearcher;
import editor.settings.CompilerSettings;
import editor.util.Experiment;
import editor.util.IProgressCallback;
import editor.util.ModalEventQueue;
import java.nio.file.Path;
import gw.util.PathUtil;
import editor.util.ProgressFeedback;
import gw.lang.reflect.IType;
import gw.lang.reflect.TypeSystem;
import gw.util.StreamUtil;
import java.awt.EventQueue;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JOptionPane;


/**
 */
public class ExperimentBuild
{
  private static ExperimentBuild INSTANCE;

  private final FileChangeFinder _fileChangeFinder;
  private List<CompiledClass> _compiledClassesNoErrors;
  private Set<IType> _errantTypes = new HashSet<>();

  public static ExperimentBuild instance()
  {
    return INSTANCE == null ? INSTANCE = new ExperimentBuild( true ) : INSTANCE;
  }

  public ExperimentBuild( boolean rebuild )
  {
    _fileChangeFinder = new FileChangeFinder( rebuild );
    _compiledClassesNoErrors = new ArrayList<>();
  }

  public boolean make( ICompileConsumer consumer )
  {
    boolean result;
    consumer = getDebugger() == null ? chainForNotDebugging( consumer ) : chainForDebugging( consumer );
    if( _fileChangeFinder.isRefreshAll() )
    {
      result = rebuild( consumer );
    }
    else
    {
      result = build( consumer, findTypesToCompile(), true );
    }
    return result;
  }

  public boolean rebuild( ICompileConsumer consumer )
  {
    cleanCompileOutput();
    copySources();
    boolean result = build( chainForNotDebugging( consumer ), Collections.singleton( FileTreeUtil.getRoot() ), false );
    TypeSystem.refresh( false );
    return result;
  }

  private void copySources()
  {
    if( !CompilerSettings.isStaticCompile() )
    {
      return;
    }
    Path outputPath = CompilerSettings.getCompilerOutputDir();
    if( !PathUtil.isDirectory( outputPath ) )
    {
      if( !PathUtil.mkdirs( outputPath ) )
      {
        JOptionPane.showMessageDialog( LabFrame.instance(), "Invalid compiler output path: " + PathUtil.getAbsolutePathName( outputPath ) );
      }
    }
    Experiment experiment = LabFrame.instance().getGosuPanel().getExperiment();
    for( String sp : experiment.getSourcePath() )
    {
      Path jarOrDir = PathUtil.getAbsolutePath( PathUtil.create( sp ) );
      if( PathUtil.isDirectory( jarOrDir ) )
      {
        for( Path child : PathUtil.listFiles( jarOrDir ) )
        {
          StreamUtil.copy( child, outputPath );
        }
      }
    }
  }

  private void cleanCompileOutput()
  {
    if( CompilerSettings.isStaticCompile() )
    {
      Path dir = CompilerSettings.getCompilerOutputDir();
      if( PathUtil.isDirectory( dir ) )
      {
        for( Path child : PathUtil.listFiles( dir ) )
        {
          PathUtil.delete( child, true );
        }
      }
    }
  }

  private Set<IType> findTypesToCompile()
  {
    Path outputPath = CompilerSettings.getCompilerOutputDir();
    Set<IType> types = new HashSet<>();
    for( FileTree ft: _fileChangeFinder.findChangedFiles( ref -> true ) )
    {
      if( ft.getType() != null )
      {
        IncrementalCompilerUsageSearcher searcher = new IncrementalCompilerUsageSearcher( ft.getType() );
        searcher.headlessSearch( FileTreeUtil.getRoot() );
        types.addAll( searcher.getTypes() );
      }
      else if( ft.isFile() && outputPath != null )
      {
        copySourceFileToOutputDir( outputPath, ft );
      }
    }
    types.addAll( _errantTypes );
    return types;
  }

  private void copySourceFileToOutputDir( Path outputPath, FileTree ft )
  {
    String fqnDir = ft.getParent().makeFqn();
    Path dir;
    if( fqnDir != null )
    {
      fqnDir = fqnDir.replace( '.', File.separatorChar );
      dir = PathUtil.create( outputPath, fqnDir );
      //noinspection ResultOfMethodCallIgnored
      PathUtil.mkdirs( dir );
    }
    else
    {
      dir = outputPath;
    }
    Path file = PathUtil.create( dir, PathUtil.getName( ft.getFileOrDir() ) );
    StreamUtil.copy( ft.getFileOrDir(), file );
  }

  private Debugger getDebugger()
  {
    return LabFrame.instance().getGosuPanel().getDebugger();
  }

  private ICompileConsumer chainForDebugging( ICompileConsumer consumer )
  {
    return cc -> {
      if( !cc.isErrant() )
      {
        _compiledClassesNoErrors.add( cc );
      }
      else
      {
        _errantTypes.add( cc.getType() );
      }
      return consumer.accept( cc );
    };
  }

  private ICompileConsumer chainForNotDebugging( ICompileConsumer consumer )
  {
    return cc -> {
      if( cc.isErrant() )
      {
        _errantTypes.add( cc.getType() );
      }
      return consumer.accept( cc );
    };
  }

  private boolean build( ICompileConsumer consumer, Set sources, boolean incremental )
  {
    GosuPanel gosuPanel = LabFrame.instance().getGosuPanel();
    _errantTypes = new HashSet<>();
    try
    {
      MessagesPanel messages = gosuPanel.showMessages( true );
      messages.clear();

      boolean[] bRes = {false};
      boolean[] bFinished = {false};
      Compiler compiler = new Compiler();
      //noinspection unchecked
      ProgressFeedback.runWithProgress( "Compiling...",
                                        incremental
                                        ? progress -> incrementalCompileSources( sources, consumer, messages, bRes, bFinished, compiler, progress )
                                        : progress -> fullCompileSources( sources, consumer, messages, bRes, bFinished, compiler, progress ) );
      new ModalEventQueue( () -> !bFinished[0] ).run();
      MessageTree doneMessage;
      if( bRes[0] )
      {
        int errors = compiler.getErrors();
        int warnings = compiler.getWarnings();
        String message = "Compilation completed with " +
                         errors + (errors == 1 ? " error " : " errors ") + " and " +
                         warnings + (warnings == 1 ? " warning " : " warnings ");
        messages.insertAtTop( doneMessage = new MessageTree( message, errors > 0 ? NodeKind.Error : warnings > 0 ? NodeKind.Warning : NodeKind.Info, MessageTree.empty() ) );
      }
      else
      {
        messages.insertAtTop( doneMessage = new MessageTree( "Compilation failed to complete", NodeKind.Failure, MessageTree.empty() ) );
      }
      EventQueue.invokeLater( doneMessage::select );
      //messages.expandAll();
      return bRes[0];
    }
    finally
    {
      _fileChangeFinder.reset();
      _compiledClassesNoErrors = new ArrayList<>();
    }
  }

  private void fullCompileSources( Collection<FileTree> sources, ICompileConsumer consumer, MessagesPanel messages, boolean[] bRes, boolean[] bFinished, Compiler compiler, IProgressCallback progress )
  {
    progress.setLength( sources.stream().mapToInt( FileTree::getTotalFiles ).sum() );
    for( FileTree fileTree: sources )
    {
      bRes[0] |= compiler.compileTree( fileTree, consumer, progress, messages );
    }
    redefineClassInDebugger();
    bFinished[0] = true;
  }

  private void incrementalCompileSources( Collection<IType> sources, ICompileConsumer consumer, MessagesPanel messages, boolean[] bRes, boolean[] bFinished, Compiler compiler, IProgressCallback progress )
  {
    try
    {
      progress.setLength( sources.size() );
      bRes[0] = true;
      for( IType type : sources )
      {
        progress.incrementProgress( type != null ? type.getName() : "" );
        bRes[0] |= compiler.compile( type, consumer, messages );
      }
      redefineClassInDebugger();
    }
    finally
    {
      bFinished[0] = true;
    }
  }

  private void redefineClassInDebugger()
  {
    Debugger debugger = getDebugger();
    if( debugger != null && !_compiledClassesNoErrors.isEmpty() )
    {
      debugger.redefineClasses( _compiledClassesNoErrors );
    }
  }
}
