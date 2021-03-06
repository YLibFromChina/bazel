// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.Set;
import javax.annotation.Nullable;

/** A StarlarkFunction is a function value created by a Starlark {@code def} statement. */
public final class StarlarkFunction implements StarlarkCallable {

  private final String name;
  private final FunctionSignature signature;
  private final Location location;
  private final ImmutableList<Statement> statements;
  private final Module module; // a function closes over its defining module
  private final Tuple<Object> defaultValues;

  // isToplevel indicates that this is the <toplevel> function containing
  // top-level statements of a file. It causes assignments to unresolved
  // identifiers to update the module, not the lexical frame.
  // TODO(adonovan): remove this hack when identifier resolution is accurate.
  boolean isToplevel;

  StarlarkFunction(
      String name,
      Location location,
      FunctionSignature signature,
      Tuple<Object> defaultValues,
      ImmutableList<Statement> statements,
      Module module) {
    this.name = name;
    this.signature = signature;
    this.location = location;
    this.statements = statements;
    this.module = module;
    this.defaultValues = defaultValues;
  }

  /**
   * Returns the optional tuple of default values for optional parameters. For example, the defaults
   * for {@code def f(a, b=1, *, c, d=2)} would be {@code (1, 2)}. May return null if the function
   * has no optional parameters.
   */
  public Tuple<Object> getDefaultValues() {
    return defaultValues;
  }

  /** Returns the signature of this function. */
  public FunctionSignature getSignature() {
    return signature;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public String getName() {
    return name;
  }

  /** Returns the value denoted by the function's doc string literal, or null if absent. */
  @Nullable
  public String getDocumentation() {
    if (statements.isEmpty()) {
      return null;
    }
    Statement first = statements.get(0);
    if (!(first instanceof ExpressionStatement)) {
      return null;
    }
    Expression expr = ((ExpressionStatement) first).getExpression();
    if (!(expr instanceof StringLiteral)) {
      return null;
    }
    return ((StringLiteral) expr).getValue();
  }

  public Module getModule() {
    return module;
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    if (thread.mutability().isFrozen()) {
      throw Starlark.errorf("Trying to call in frozen environment");
    }
    if (thread.isRecursiveCall(this)) {
      throw Starlark.errorf("function '%s' called recursively", name);
    }

    // Compute the effective parameter values
    // and update the corresponding variables.
    Object[] arguments =
        matchSignature(
            getSignature(), this, getDefaultValues(), thread.mutability(), positional, named);

    StarlarkThread.Frame fr = thread.frame(0);
    ImmutableList<String> names = getSignature().getParameterNames();
    for (int i = 0; i < names.size(); ++i) {
      fr.locals.put(names.get(i), arguments[i]);
    }

    return Eval.execFunctionBody(fr, statements);
  }

  @Override
  public void repr(Printer printer) {
    Object label = module.getLabel();

    printer.append("<function " + getName());
    if (label != null) {
      printer.append(" from " + label);
    }
    printer.append(">");
  }

  /**
   * Checks the {@code positional} and {@code named} arguments to ensure they match the {@code
   * signature}. It returns an array of effective parameter values corresponding to the parameters
   * of the signature. Newly allocated values (e.g. a {@code **kwargs} dict) use the Mutability
   * {@code mu}.
   *
   * <p>If the function has optional parameters, their default values must be supplied by {@code
   * defaults}; see {@link getDefaultValues} for details.
   *
   * <p>The caller is responsible for accessing the correct element and casting to an appropriate
   * type.
   *
   * <p>On failure, it throws an EvalException incorporating {@code func.toString()}.
   */
  private static Object[] matchSignature(
      FunctionSignature signature,
      StarlarkCallable func, // only used in error messages
      @Nullable Tuple<Object> defaults,
      @Nullable Mutability mu,
      Object[] positional,
      Object[] named)
      throws EvalException {
    // TODO(adonovan): simplify this function. Combine cases 1 and 2 without loss of efficiency.
    // TODO(adonovan): reduce the verbosity of errors. Printing func.toString is often excessive.
    // Make the error messages consistent in form.
    // TODO(adonovan): report an error if there were missing values in 'defaults'.

    Object[] arguments = new Object[signature.numParameters()];

    ImmutableList<String> names = signature.getParameterNames();

    // Note that this variable will be adjusted down if there are extra positionals,
    // after these extra positionals are dumped into starParam.
    int numPositionalArgs = positional.length;

    int numMandatoryPositionalParams = signature.numMandatoryPositionals();
    int numOptionalPositionalParams = signature.numOptionalPositionals();
    int numMandatoryNamedOnlyParams = signature.numMandatoryNamedOnly();
    int numOptionalNamedOnlyParams = signature.numOptionalNamedOnly();
    boolean hasVarargs = signature.hasVarargs();
    boolean hasKwargs = signature.hasKwargs();
    int numPositionalParams = numMandatoryPositionalParams + numOptionalPositionalParams;
    int numNamedOnlyParams = numMandatoryNamedOnlyParams + numOptionalNamedOnlyParams;
    int numNamedParams = numPositionalParams + numNamedOnlyParams;
    int kwargIndex = names.size() - 1; // only valid if hasKwargs

    // (1) handle positional arguments
    if (hasVarargs) {
      // Nota Bene: we collect extra positional arguments in a (tuple,) rather than a [list],
      // and this is actually the same as in Python.
      Object varargs;
      if (numPositionalArgs > numPositionalParams) {
        varargs =
            Tuple.wrap(Arrays.copyOfRange(positional, numPositionalParams, numPositionalArgs));
        numPositionalArgs = numPositionalParams; // clip numPositionalArgs
      } else {
        varargs = Tuple.empty();
      }
      arguments[numNamedParams] = varargs;
    } else if (numPositionalArgs > numPositionalParams) {
      throw new EvalException(
          null,
          numPositionalParams > 0
              ? "too many (" + numPositionalArgs + ") positional arguments in call to " + func
              : func + " does not accept positional arguments, but got " + numPositionalArgs);
    }

    for (int i = 0; i < numPositionalArgs; i++) {
      arguments[i] = positional[i];
    }

    // (2) handle keyword arguments
    if (named.length == 0) {
      // Easy case (2a): there are no keyword arguments.
      // All arguments were positional, so check we had enough to fill all mandatory positionals.
      if (numPositionalArgs < numMandatoryPositionalParams) {
        throw Starlark.errorf(
            "insufficient arguments received by %s (got %s, expected at least %s)",
            func, numPositionalArgs, numMandatoryPositionalParams);
      }
      // We had no named argument, so fail if there were mandatory named-only parameters
      if (numMandatoryNamedOnlyParams > 0) {
        throw Starlark.errorf("missing mandatory keyword arguments in call to %s", func);
      }
      // Fill in defaults for missing optional parameters, that were conveniently grouped together,
      // thanks to the absence of mandatory named-only parameters as checked above.
      if (defaults != null) {
        int endOptionalParams = numPositionalParams + numOptionalNamedOnlyParams;
        for (int i = numPositionalArgs; i < endOptionalParams; i++) {
          arguments[i] = defaults.get(i - numMandatoryPositionalParams);
        }
      }
      // If there's a kwarg, it's empty.
      if (hasKwargs) {
        arguments[kwargIndex] = Dict.of(mu);
      }
    } else {
      // general case (2b): some keyword arguments may correspond to named parameters
      Dict<String, Object> kwargs = hasKwargs ? Dict.of(mu) : null;

      // Accept the named arguments that were passed.
      for (int i = 0; i < named.length; i += 2) {
        String keyword = (String) named[i]; // safe
        Object value = named[i + 1];
        int pos = names.indexOf(keyword); // the list should be short, so linear scan is OK.
        if (0 <= pos && pos < numNamedParams) {
          if (arguments[pos] != null) {
            throw Starlark.errorf("%s got multiple values for parameter '%s'", func, keyword);
          }
          arguments[pos] = value;
        } else {
          if (!hasKwargs) {
            Set<String> unexpected = Sets.newHashSet();
            for (int j = 0; j < named.length; j += 2) {
              unexpected.add((String) named[j]);
            }
            unexpected.removeAll(names.subList(0, numNamedParams));
            // TODO(adonovan): do spelling check.
            throw Starlark.errorf(
                "unexpected keyword%s '%s' in call to %s",
                unexpected.size() > 1 ? "s" : "",
                Joiner.on("', '").join(Ordering.natural().sortedCopy(unexpected)),
                func);
          }
          int sz = kwargs.size();
          kwargs.put(keyword, value, null);
          if (kwargs.size() == sz) {
            throw Starlark.errorf(
                "%s got multiple values for keyword argument '%s'", func, keyword);
          }
        }
      }
      if (hasKwargs) {
        arguments[kwargIndex] = kwargs;
      }

      // Check that all mandatory parameters were filled in general case 2b.
      // Note: it's possible that numPositionalArgs > numMandatoryPositionalParams but that's OK.
      for (int i = numPositionalArgs; i < numMandatoryPositionalParams; i++) {
        if (arguments[i] == null) {
          throw Starlark.errorf(
              "missing mandatory positional argument '%s' while calling %s", names.get(i), func);
        }
      }

      int endMandatoryNamedOnlyParams = numPositionalParams + numMandatoryNamedOnlyParams;
      for (int i = numPositionalParams; i < endMandatoryNamedOnlyParams; i++) {
        if (arguments[i] == null) {
          throw Starlark.errorf(
              "missing mandatory named-only argument '%s' while calling %s", names.get(i), func);
        }
      }

      // Get defaults for those parameters that weren't passed.
      if (defaults != null) {
        for (int i = Math.max(numPositionalArgs, numMandatoryPositionalParams);
            i < numPositionalParams;
            i++) {
          if (arguments[i] == null) {
            arguments[i] = defaults.get(i - numMandatoryPositionalParams);
          }
        }
        int numMandatoryParams = numMandatoryPositionalParams + numMandatoryNamedOnlyParams;
        for (int i = numMandatoryParams + numOptionalPositionalParams; i < numNamedParams; i++) {
          if (arguments[i] == null) {
            arguments[i] = defaults.get(i - numMandatoryParams);
          }
        }
      }
    } // End of general case 2b for argument passing.

    return arguments;
  }

  /** Render this object in the form of an equivalent Python function signature. */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getName());
    sb.append('(');
    getSignature().toStringBuilder(sb, this::printDefaultValue);
    sb.append(')');
    return sb.toString();
  }

  private String printDefaultValue(int i) {
    Tuple<Object> defaultValues = getDefaultValues();
    Object v = defaultValues != null ? defaultValues.get(i) : null;
    return v != null ? Starlark.repr(v) : null;
  }

  @Override
  public boolean isImmutable() {
    // Only correct because closures are not yet supported.
    return true;
  }
}
