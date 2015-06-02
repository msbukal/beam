/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk;

import com.google.cloud.dataflow.sdk.coders.CoderRegistry;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.runners.PipelineRunner;
import com.google.cloud.dataflow.sdk.runners.TransformHierarchy;
import com.google.cloud.dataflow.sdk.runners.TransformTreeNode;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.util.UserCodeException;
import com.google.cloud.dataflow.sdk.values.PBegin;
import com.google.cloud.dataflow.sdk.values.PInput;
import com.google.cloud.dataflow.sdk.values.POutput;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@code Pipeline} manages a DAG of {@link PTransform}s, and the
 * {@link com.google.cloud.dataflow.sdk.values.PCollection}s
 * that the {@link PTransform}s consume and produce.
 *
 * <p> After a {@code Pipeline} has been constructed, it can be executed,
 * using a default or an explicit {@link PipelineRunner}.
 *
 * <p> Multiple {@code Pipeline}s can be constructed and executed independently
 * and concurrently.
 *
 * <p> Each {@code Pipeline} is self-contained and isolated from any other
 * {@code Pipeline}.  The {@link PValue PValues} that are inputs and outputs of each of a
 * {@code Pipeline}'s {@link PTransform PTransforms} are also owned by that {@code Pipeline}.
 * A {@code PValue} owned by one {@code Pipeline} can be read only by {@code PTransform}s
 * also owned by that {@code Pipeline}.
 *
 * <p> Here's a typical example of use:
 * <pre> {@code
 * // Start by defining the options for the pipeline.
 * PipelineOptions options = PipelineOptionsFactory.create();
 * // Then create the pipeline.
 * Pipeline p = Pipeline.create(options);
 *
 * // A root PTransform, like TextIO.Read or Create, gets added
 * // to the Pipeline by being applied:
 * PCollection<String> lines =
 *     p.apply(TextIO.Read.from("gs://bucket/dir/file*.txt"));
 *
 * // A Pipeline can have multiple root transforms:
 * PCollection<String> moreLines =
 *     p.apply(TextIO.Read.from("gs://bucket/other/dir/file*.txt"));
 * PCollection<String> yetMoreLines =
 *     p.apply(Create.of("yet", "more", "lines")).setCoder(StringUtf8Coder.of());
 *
 * // Further PTransforms can be applied, in an arbitrary (acyclic) graph.
 * // Subsequent PTransforms (and intermediate PCollections etc.) are
 * // implicitly part of the same Pipeline.
 * PCollection<String> allLines =
 *     PCollectionList.of(lines).and(moreLines).and(yetMoreLines)
 *     .apply(new Flatten<String>());
 * PCollection<KV<String, Integer>> wordCounts =
 *     allLines
 *     .apply(ParDo.of(new ExtractWords()))
 *     .apply(new Count<String>());
 * PCollection<String> formattedWordCounts =
 *     wordCounts.apply(ParDo.of(new FormatCounts()));
 * formattedWordCounts.apply(TextIO.Write.to("gs://bucket/dir/counts.txt"));
 *
 * // PTransforms aren't executed when they're applied, rather they're
 * // just added to the Pipeline.  Once the whole Pipeline of PTransforms
 * // is constructed, the Pipeline's PTransforms can be run using a
 * // PipelineRunner.  The default PipelineRunner executes the Pipeline
 * // directly, sequentially, in this one process, which is useful for
 * // unit tests and simple experiments:
 * p.run();
 *
 * } </pre>
 */
public class Pipeline {
  private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

  /////////////////////////////////////////////////////////////////////////////
  // Public operations.

  /**
   * Constructs a pipeline from the provided options.
   *
   * @return The newly created pipeline.
   */
  public static Pipeline create(PipelineOptions options) {
    Pipeline pipeline = new Pipeline(PipelineRunner.fromOptions(options), options);
    LOG.debug("Creating {}", pipeline);
    return pipeline;
  }

  /**
   * Returns a {@link PBegin} owned by this Pipeline.  This is useful
   * as the input of a root PTransform such as {@code TextIO.Read} or
   * {@link com.google.cloud.dataflow.sdk.transforms.Create}.
   */
  public PBegin begin() {
    return PBegin.in(this);
  }

  /**
   * Like {@link #apply(String, PTransform)} but defaulting to the name
   * of the {@code PTransform}.
   */
  public <OutputT extends POutput> OutputT apply(
      PTransform<? super PBegin, OutputT> root) {
    return begin().apply(root);
  }

  /**
   * Starts using this pipeline with a root {@code PTransform} such as
   * {@code TextIO.READ} or {@link com.google.cloud.dataflow.sdk.transforms.Create}.
   * This specific call to {@code apply} is identified by the provided {@code name}.
   * This name is used in various places, including the monitoring UI, logging,
   * and to stably identify this application node in the job graph.
   *
   * <p> Alias for {@code begin().apply(name, root)}.
   */
  public <OutputT extends POutput> OutputT apply(
      String name, PTransform<? super PBegin, OutputT> root) {
    return begin().apply(name, root);
  }

  /**
   * Runs the Pipeline.
   */
  public PipelineResult run() {
    LOG.debug("Running {} via {}", this, runner);
    try {
      return runner.run(this);
    } catch (UserCodeException e) {
      // This serves to replace the stack with one that ends here and
      // is caused by the caught UserCodeException, thereby splicing
      // out all the stack frames in between the PipelineRunner itself
      // and where the worker calls into the user's code.
      throw new RuntimeException(e.getCause());
    }
  }


  /////////////////////////////////////////////////////////////////////////////
  // Below here are operations that aren't normally called by users.

  /**
   * Returns the {@link CoderRegistry} that this Pipeline uses.
   */
  public CoderRegistry getCoderRegistry() {
    if (coderRegistry == null) {
      coderRegistry = new CoderRegistry();
      coderRegistry.registerStandardCoders();
    }
    return coderRegistry;
  }

  /**
   * Sets the {@link CoderRegistry} that this Pipeline uses.
   */
  public void setCoderRegistry(CoderRegistry coderRegistry) {
    this.coderRegistry = coderRegistry;
  }

  /**
   * A {@link PipelineVisitor} can be passed into
   * {@link Pipeline#traverseTopologically} to be called for each of the
   * transforms and values in the Pipeline.
   */
  public interface PipelineVisitor {
    public void enterCompositeTransform(TransformTreeNode node);
    public void leaveCompositeTransform(TransformTreeNode node);
    public void visitTransform(TransformTreeNode node);
    public void visitValue(PValue value, TransformTreeNode producer);
  }

  /**
   * Invokes the PipelineVisitor's
   * {@link PipelineVisitor#visitTransform} and
   * {@link PipelineVisitor#visitValue} operations on each of this
   * Pipeline's PTransforms and PValues, in forward
   * topological order.
   *
   * <p> Traversal of the pipeline causes PTransform and PValue instances to
   * be marked as finished, at which point they may no longer be modified.
   *
   * <p> Typically invoked by {@link PipelineRunner} subclasses.
   */
  public void traverseTopologically(PipelineVisitor visitor) {
    Set<PValue> visitedValues = new HashSet<>();
    // Visit all the transforms, which should implicitly visit all the values.
    transforms.visit(visitor, visitedValues);
    if (!visitedValues.containsAll(values)) {
      throw new RuntimeException(
          "internal error: should have visited all the values "
          + "after visiting all the transforms");
    }
  }

  /**
   * Like {@link #applyTransform(String, PInput, PTransform)} but defaulting to the name
   * provided by the {@link PTransform}.
   */
  public static <InputT extends PInput, OutputT extends POutput>
  OutputT applyTransform(InputT input,
      PTransform<? super InputT, OutputT> transform) {
    return input.getPipeline().applyInternal(transform.getName(), input, transform);
  }

  /**
   * Applies the given {@code PTransform} to this input {@code InputT} and returns
   * its {@code OutputT}. This uses {@code name} to identify this specific application
   * of the transform. This name is used in various places, including the monitoring UI,
   * logging, and to stably identify this application node in the job graph.
   *
   * <p> Called by {@link PInput} subclasses in their {@code apply} methods.
   */
  public static <InputT extends PInput, OutputT extends POutput>
  OutputT applyTransform(String name, InputT input,
      PTransform<? super InputT, OutputT> transform) {
    return input.getPipeline().applyInternal(name, input, transform);
  }

  /////////////////////////////////////////////////////////////////////////////
  // Below here are internal operations, never called by users.

  private final PipelineRunner<?> runner;
  private final PipelineOptions options;
  private final TransformHierarchy transforms = new TransformHierarchy();
  private Collection<PValue> values = new ArrayList<>();
  private Set<String> usedFullNames = new HashSet<>();
  private CoderRegistry coderRegistry;
  private Multimap<PTransform<?, ?>, AppliedPTransform<?, ?, ?>> transformApplicationsForTesting =
      HashMultimap.create();

  /**
   * @deprecated replaced by {@link #Pipeline(PipelineRunner, PipelineOptions)}
   */
  @Deprecated
  protected Pipeline(PipelineRunner<?> runner) {
    this(runner, PipelineOptionsFactory.create());
  }

  protected Pipeline(PipelineRunner<?> runner, PipelineOptions options) {
    this.runner = runner;
    this.options = options;
  }

  @Override
  public String toString() {
    return "Pipeline#" + hashCode();
  }

  /**
   * Applies a transformation to the given input.
   *
   * @see Pipeline#apply
   */
  private <InputT extends PInput, OutputT extends POutput>
  OutputT applyInternal(String name, InputT input,
      PTransform<? super InputT, OutputT> transform) {
    input.finishSpecifying();

    TransformTreeNode parent = transforms.getCurrent();
    String namePrefix = parent.getFullName();

    String fullName = uniquifyInternal(namePrefix, name);

    boolean nameIsUnique = fullName.equals(buildName(namePrefix, name));

    if (!nameIsUnique) {
      switch (getOptions().getStableUniqueNames()) {
        case OFF:
          break;
        case WARNING:
          LOG.warn("Transform {} does not have a stable unique name. "
              + "This will prevent reloading of pipelines.", fullName);
          break;
        case ERROR:
          throw new IllegalStateException(
              "Transform " + fullName + " does not have a stable unique name. "
              + "This will prevent reloading of pipelines.");
        default:
          throw new IllegalArgumentException(
              "Unrecognized value for stable unique names: " + getOptions().getStableUniqueNames());
      }
    }

    TransformTreeNode child =
        new TransformTreeNode(parent, transform, fullName, input);
    parent.addComposite(child);

    transforms.addInput(child, input);

    LOG.debug("Adding {} to {}", transform, this);
    try {
      transforms.pushNode(child);
      transform.validate(input);
      OutputT output = runner.apply(transform, input);
      transforms.setOutput(child, output);

      AppliedPTransform<?, ?, ?> applied = AppliedPTransform.of(
          child.getFullName(), input, output, transform);
      transformApplicationsForTesting.put(transform, applied);
      // recordAsOutput is a NOOP if already called;
      output.recordAsOutput(applied);
      verifyOutputState(output, child);
      return output;
    } finally {
      transforms.popNode();
    }
  }

  /**
   * Returns all producing transforms for the {@link PValue PValues} contained
   * in {@code output}.
   */
  private List<AppliedPTransform<?, ?, ?>> getProducingTransforms(POutput output) {
    List<AppliedPTransform<?, ?, ?>> producingTransforms = new ArrayList<>();
    for (PValue value : output.expand()) {
      AppliedPTransform<?, ?, ?> transform = value.getProducingTransformInternal();
      if (transform != null) {
        producingTransforms.add(transform);
      }
    }
    return producingTransforms;
  }

  /**
   * Verifies that the output of a PTransform is correctly defined.
   *
   * <p> A non-composite transform must have all
   * of its outputs registered as produced by the transform.
   *
   * <p> A composite transform must have all of its outputs
   * registered as produced by the contains primitive transforms.
   * They have each had the above check performed already, when
   * they were applied, so the only possible failure state is
   * that the composite transform has returned a primitive output.
   */
  private void verifyOutputState(POutput output, TransformTreeNode node) {
    if (!node.isCompositeNode()) {
      PTransform<?, ?> thisTransform = node.getTransform();
      List<AppliedPTransform<?, ?, ?>> producingTransforms = getProducingTransforms(output);
      for (AppliedPTransform<?, ?, ?> producingTransform : producingTransforms) {
        // Using != because object identity indicates that the transforms
        // are the same node in the pipeline
        if (thisTransform != producingTransform.getTransform()) {
          throw new IllegalArgumentException("Output of non-composite transform "
              + thisTransform + " is registered as being produced by"
              + " a different transform: " + producingTransform);
        }
      }
    } else {
      PTransform<?, ?> thisTransform = node.getTransform();
      List<AppliedPTransform<?, ?, ?>> producingTransforms = getProducingTransforms(output);
      for (AppliedPTransform<?, ?, ?> producingTransform : producingTransforms) {
        // Using == because object identity indicates that the transforms
        // are the same node in the pipeline
        if (thisTransform == producingTransform.getTransform()) {
          throw new IllegalStateException("Output of composite transform "
              + thisTransform + " is registered as being produced by it,"
              + " but the output of every composite transform should be"
              + " produced by a primitive transform contained therein.");
        }
      }
    }
  }

  /**
   * Returns the configured pipeline runner.
   */
  public PipelineRunner<?> getRunner() {
    return runner;
  }

  /**
   * Returns the configured pipeline options.
   */
  public PipelineOptions getOptions() {
    return options;
  }

  /**
   * Returns the fully qualified name of a transform for testing.
   *
   * @throws IllegalStateException if the transform has not been applied to the pipeline
   * or was applied multiple times.
   */
  @Deprecated
  public String getFullNameForTesting(PTransform<?, ?> transform) {
    Collection<AppliedPTransform<?, ?, ?>> uses =
        transformApplicationsForTesting.get(transform);
    Preconditions.checkState(uses.size() > 0, "Unknown transform: " + transform);
    Preconditions.checkState(uses.size() <= 1, "Transform used multiple times: " + transform);
    return Iterables.getOnlyElement(uses).getFullName();
  }

  /**
   * Returns a unique name for a transform with the given prefix (from
   * enclosing transforms) and initial name.
   *
   * <p> For internal use only.
   */
  private String uniquifyInternal(String namePrefix, String origName) {
    String name = origName;
    int suffixNum = 2;
    while (true) {
      String candidate = buildName(namePrefix, name);
      if (usedFullNames.add(candidate)) {
        return candidate;
      }
      // A duplicate!  Retry.
      name = origName + suffixNum++;
    }
  }

  /**
   * Builds a name from a /-delimited prefix and a name.
   */
  private String buildName(String namePrefix, String name) {
    return namePrefix.isEmpty() ? name : namePrefix + "/" + name;
  }

  /**
   * Adds the given PValue to this Pipeline.
   *
   * <p> For internal use only.
   */
  public void addValueInternal(PValue value) {
    this.values.add(value);
    LOG.debug("Adding {} to {}", value, this);
  }
}
