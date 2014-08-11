/*******************************************************************************
 * Copyright (c) 2014 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.enigma.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.bytecode.Descriptor;
import javassist.bytecode.MethodInfo;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import cuchaz.enigma.Constants;
import cuchaz.enigma.mapping.ClassEntry;
import cuchaz.enigma.mapping.MethodEntry;
import cuchaz.enigma.mapping.Translator;

public class Ancestries implements Serializable
{
	private static final long serialVersionUID = 738687982126844179L;
	
	private Map<String,String> m_superclasses;
	private Multimap<String,String> m_methodImplementations;
	
	public Ancestries( )
	{
		m_superclasses = Maps.newHashMap();
		m_methodImplementations = HashMultimap.create();
	}
	
	@SuppressWarnings( "unchecked" )
	public void readFromJar( InputStream in )
	throws IOException
	{
		ClassPool classPool = new ClassPool();
		
		ZipInputStream zin = new ZipInputStream( in );
		ZipEntry entry;
		while( ( entry = zin.getNextEntry() ) != null )
		{
			// filter out non-classes
			if( entry.isDirectory() || !entry.getName().endsWith( ".class" ) )
			{
				continue;
			}
			
			// read the class into a buffer
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[Constants.KiB];
			int totalNumBytesRead = 0;
			while( zin.available() > 0 )
			{
				int numBytesRead = zin.read( buf );
				if( numBytesRead < 0 )
				{
					break;
				}
				bos.write( buf, 0, numBytesRead );
				
				// sanity checking
				totalNumBytesRead += numBytesRead;
				if( totalNumBytesRead > Constants.MiB )
				{
					throw new Error( "Class file " + entry.getName() + " larger than 1 MiB! Something is wrong!" );
				}
			}
			
			// determine the class name (ie chop off the ".class")
			String className = Descriptor.toJavaName( entry.getName().substring( 0, entry.getName().length() - ".class".length() ) );
			
			// get a javassist handle for the class
			classPool.insertClassPath( new ByteArrayClassPath( className, bos.toByteArray() ) );
			try
			{
				CtClass c = classPool.get( className );
				addSuperclass( c.getName(), c.getClassFile().getSuperclass() );
				addMethodImplementations( c.getName(), (List<MethodInfo>)c.getClassFile().getMethods() );
			}
			catch( NotFoundException ex )
			{
				throw new Error( "Unable to load class: " + className );
			}
		}
	}
	
	private void addMethodImplementations( String name, List<MethodInfo> methods )
	{
		for( MethodInfo method : methods )
		{
			m_methodImplementations.put( name, getMethodKey( method.getName(), method.getDescriptor() ) );
		}
	}

	public void addSuperclass( String className, String superclassName )
	{
		className = Descriptor.toJvmName( className );
		superclassName = Descriptor.toJvmName( superclassName );
		
		if( className.equals( superclassName ) )
		{
			throw new IllegalArgumentException( "Class cannot be its own superclass! " + className );
		}
		
		if( !isJre( className ) && !isJre( superclassName ) )
		{
			m_superclasses.put( className, superclassName );
		}
	}
	
	public String getSuperclassName( String className )
	{
		return m_superclasses.get( className );
	}
	
	public List<String> getAncestry( String className )
	{
		List<String> ancestors = new ArrayList<String>();
		while( className != null )
		{
			className = getSuperclassName( className );
			if( className != null )
			{
				ancestors.add( className );
			}
		}
		return ancestors;
	}
	
	public List<String> getSubclasses( String className )
	{
		// linear search is fast enough for now
		List<String> subclasses = Lists.newArrayList();
		for( Map.Entry<String,String> entry : m_superclasses.entrySet() )
		{
			String subclass = entry.getKey();
			String superclass = entry.getValue();
			if( className.equals( superclass ) )
			{
				subclasses.add( subclass );
			}
		}
		return subclasses;
	}
	
	public boolean isMethodImplemented( MethodEntry methodEntry )
	{
		return isMethodImplemented( methodEntry.getClassName(), methodEntry.getName(), methodEntry.getSignature() );
	}
	
	public boolean isMethodImplemented( String className, String methodName, String methodSignature )
	{
		Collection<String> implementations = m_methodImplementations.get( className );
		if( implementations == null )
		{
			return false;
		}
		return implementations.contains( getMethodKey( methodName, methodSignature ) );
	}
	
	public ClassInheritanceTreeNode getClassInheritance( Translator deobfuscatingTranslator, ClassEntry obfClassEntry )
	{
		// get the root node
		List<String> ancestry = getAncestry( obfClassEntry.getName() );
		ClassInheritanceTreeNode rootNode = new ClassInheritanceTreeNode( deobfuscatingTranslator, ancestry.get( ancestry.size() - 1 ) );
		
		// expand all children recursively
		rootNode.load( this, true );
		
		return rootNode;
	}
	
	public MethodInheritanceTreeNode getMethodInheritance( Translator deobfuscatingTranslator, MethodEntry obfMethodEntry )
	{
		// travel to the ancestor implementation
		String baseImplementationClassName = obfMethodEntry.getClassName();
		for( String ancestorClassName : getAncestry( obfMethodEntry.getClassName() ) )
		{
			if( isMethodImplemented( ancestorClassName, obfMethodEntry.getName(), obfMethodEntry.getSignature() ) )
			{
				baseImplementationClassName = ancestorClassName;
			}
		}
		
		// make a root node at the base
		MethodEntry methodEntry = new MethodEntry(
			new ClassEntry( baseImplementationClassName ),
			obfMethodEntry.getName(),
			obfMethodEntry.getSignature()
		);
		MethodInheritanceTreeNode rootNode = new MethodInheritanceTreeNode(
			deobfuscatingTranslator,
			methodEntry,
			isMethodImplemented( methodEntry )
		);
		
		// expand the full tree
		rootNode.load( this, true );
		
		return rootNode;
	}
	
	private boolean isJre( String className )
	{
		return className.startsWith( "java/" )
			|| className.startsWith( "javax/" );
	}
	
	private String getMethodKey( String name, String signature )
	{
		return name + signature;
	}
}