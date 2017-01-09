package provingground

import edu.stanford.nlp._
import simple._
import edu.stanford.nlp.trees.Tree

object PennTrees {
  object Leaf {
    def unapply(t: Tree) = {
      if (t.isLeaf) Some(t.value) else None
    }
  }

  object Node {
    def unapply(t: Tree) = {
      if (t.isLeaf) None else Some((t.value, t.children().toList))
    }
  }

  def model(t: Tree): TreeModel = t match {
    case Leaf(s)           => TreeModel.Leaf(s)
    case Node(s, children) => TreeModel.Node(s, children map model)
  }

  implicit class ShowModel(sent: Sentence){
    def show = model(sent.parse)
  }

  implicit class ShowString(st: String){
    def show = model(new Sentence(st).parse)

    def sentence = new Sentence(st)
  }

}

sealed trait TreeModel

object TreeModel{
case class Leaf(value: String) extends TreeModel {
  override def toString = s"""Leaf("$value")"""
}

case class Node(value: String, children: List[TreeModel])
    extends TreeModel {
  // override def toString = s"""Node("$value", ${children})"""
}

}
