/*
 * #%L
 * A plugin for managing SciJava-based projects.
 * %%
 * Copyright (C) 2014 - 2015 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.maven.plugin.enforcer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

/**
 * Abstract rule for when the content of the artifacts matters.
 * 
 * @author Robert Scholte
 *
 */
public abstract class AbstractResolveDependencies implements EnforcerRule
{

    private transient DependencyTreeBuilder treeBuilder;
    
    private transient ArtifactResolver resolver;
    
    private transient ArtifactRepository localRepository;
    
    private transient List<ArtifactRepository> remoteRepositories;
    
    private transient EnforcerRuleHelper ruleHelper;

    @Override
    public void execute( EnforcerRuleHelper helper )
        throws EnforcerRuleException
    {
        ruleHelper = helper;

        // Get components
        try
        {
            resolver = (ArtifactResolver) helper.getComponent( ArtifactResolver.class );
            
            treeBuilder = (DependencyTreeBuilder) helper.getComponent( DependencyTreeBuilder.class );
        }
        catch ( ComponentLookupException e )
        {
                throw new EnforcerRuleException( "Unable to lookup DependencyTreeBuilder: ", e );
        }
        
        // Resolve expressions
        MavenProject project;
        try
        {
            project = (MavenProject) helper.evaluate( "${project}" );
            
            localRepository = (ArtifactRepository) helper.evaluate( "${localRepository}" );
            
            @SuppressWarnings("unchecked")
            final List<ArtifactRepository> result = (List<ArtifactRepository>) helper.evaluate( "${project.remoteArtifactRepositories}" );
            remoteRepositories = result;
        }
        catch ( ExpressionEvaluationException e )
        {
            throw new EnforcerRuleException( "Unable to lookup an expression " + e.getLocalizedMessage(), e );
        }
        
        Set<Artifact> artifacts = getDependenciesToCheck( project, localRepository );
        
        handleArtifacts( artifacts ); 
    }

    protected abstract void handleArtifacts( Set<Artifact> artifacts ) throws EnforcerRuleException;
    
    protected boolean isSearchTransitive()
    {
        return true;
    }
        
    private Set<Artifact> getDependenciesToCheck( MavenProject project, ArtifactRepository repository )
    {
        Set<Artifact> dependencies = null;
        try
        {
            DependencyNode node = treeBuilder.buildDependencyTree( project, repository, null );
            
            if( isSearchTransitive() )
            {
                dependencies  = getAllDescendants( node );
            }
            else if ( node.getChildren() != null )
            {
                dependencies = new HashSet<Artifact>();
                for( DependencyNode depNode : node.getChildren() )
                {
                    dependencies.add( depNode.getArtifact() );
                }
            }
        }
        catch ( DependencyTreeBuilderException e )
        {
            // otherwise we need to change the signature of this protected method
            throw new RuntimeException( e );
        }
        return dependencies;
    }

    private Set<Artifact> getAllDescendants( DependencyNode node )
    {
        Set<Artifact> children = null; 
        if( node.getChildren() != null )
        {
            children = new HashSet<Artifact>();
            for( DependencyNode depNode : node.getChildren() )
            {
                try
                {
                  if ( depNode.getState() == DependencyNode.INCLUDED ) {
                    Artifact artifact = depNode.getArtifact();
    
                    resolver.resolve( artifact, remoteRepositories, localRepository );
                    
                    children.add( artifact );
    
                    Set<Artifact> subNodes = getAllDescendants( depNode );
                    
                    if( subNodes != null )
                    {
                        children.addAll(subNodes);
                    }
                  }
                }
                catch ( ArtifactResolutionException e )
                {
                    getLog().warn( e.getMessage() );
                }
                catch ( ArtifactNotFoundException e )
                {
                    getLog().warn( e.getMessage() );
                }
            }
        }
        return children;
    }

    protected Log getLog()
    {
        return ruleHelper.getLog();
    }

    @Override
    public boolean isCacheable()
    {
        return false;
    }

    @Override
    public boolean isResultValid( EnforcerRule enforcerRule )
    {
        return false;
    }

    @Override
    public String getCacheId()
    {
        return "Does not matter as not cacheable";
    }


    /**
     * Convert a wildcard into a regex.
     *
     * @param wildcard the wildcard to convert.
     * @return the equivalent regex.
     */
    protected static String asRegex(String wildcard)
    {
        StringBuilder result = new StringBuilder( wildcard.length() );
        result.append( '^' );
        for ( int index = 0; index < wildcard.length(); index++ )
        {
            char character = wildcard.charAt( index );
            switch ( character )
            {
                case '*':
                    result.append( ".*" );
                    break;
                case '?':
                    result.append( "." );
                    break;
                case '$':
                case '(':
                case ')':
                case '.':
                case '[':
                case '\\':
                case ']':
                case '^':
                case '{':
                case '|':
                case '}':
                    result.append( "\\" );
                    result.append( character );
                    break;
                default:
                    result.append( character );
                    break;
            }
        }
        result.append( "(\\.class)?" );
        result.append( '$' );
        return result.toString();
    }

    /**
     *
     */
    protected class IgnorableDependency
    {
        public Pattern groupId;
        public Pattern artifactId;
        public Pattern classifier;
        public Pattern type;
        public List<Pattern> ignorePatterns = new ArrayList<Pattern>();

        public IgnorableDependency applyIgnoreClasses( String[] ignores, boolean indent )
        {
            String prefix = indent ? "  " : "";
            for ( String ignore : ignores )
            {
                getLog().info( prefix + "Adding ignore: " + ignore );
                ignore = ignore.replace( '.', '/' );
                String pattern = asRegex( ignore );
                getLog().debug( prefix + "Ignore: " + ignore + " maps to regex " + pattern );
                ignorePatterns.add( Pattern.compile( pattern ) );
            }
            return this;
        }

        public boolean matchesArtifact( Artifact dup )
        {
            return ( artifactId == null || artifactId.matcher( dup.getArtifactId() ).matches() )
                && ( groupId == null || groupId.matcher( dup.getGroupId() ).matches() )
                && ( classifier == null || classifier.matcher( dup.getClassifier() ).matches() )
                && ( type == null || type.matcher( dup.getType() ).matches() );
        }

        public boolean matches(String className)
        {
            for ( Pattern p : ignorePatterns )
            {
                if ( p.matcher( className ).matches() )
                {
                    return true;
                }
            }
            return false;
        }
    }
}
