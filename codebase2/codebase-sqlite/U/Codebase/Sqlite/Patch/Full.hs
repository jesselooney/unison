module U.Codebase.Sqlite.Patch.Full where

import Control.Lens
import Data.Map (Map)
import Data.Set (Set)
import Data.Set qualified as Set
import U.Codebase.Reference (Reference')
import U.Codebase.Reference qualified as Reference
import U.Codebase.Referent (Referent')
import U.Codebase.Referent qualified as Referent
import U.Codebase.Sqlite.DbId qualified as Db
import U.Codebase.Sqlite.LocalIds (LocalDefnId, LocalHashId, LocalTextId)
import U.Codebase.Sqlite.Patch.TermEdit (TermEdit')
import U.Codebase.Sqlite.Patch.TermEdit qualified as TermEdit
import U.Codebase.Sqlite.Patch.TypeEdit (TypeEdit')
import U.Codebase.Sqlite.Patch.TypeEdit qualified as TypeEdit
import Unison.Util.Map qualified as Map
import Unison.Util.Set qualified as Set

-- |
-- @
-- LocalPatch
--   { termEdits :: Map ReferentH (Set TermEdit),
--     typeEdits :: Map ReferenceH (Set TypeEdit)
--   }
-- @
type Patch = Patch' Db.TextId Db.HashId Db.ObjectId

-- |
-- @
-- LocalPatch
--   { termEdits :: Map LocalReferentH (Set LocalTermEdit),
--     typeEdits :: Map LocalReferenceH (Set LocalTypeEdit)
--   }
-- @
type LocalPatch = Patch' LocalTextId LocalHashId LocalDefnId

type Referent'' t h = Referent' (Reference' t h) (Reference' t h)

data Patch' t h o = Patch
  { termEdits :: Map (Referent'' t h) (Set (TermEdit' t o)),
    typeEdits :: Map (Reference' t h) (Set (TypeEdit' t o))
  }

patchH_ :: (Ord t, Ord h') => Traversal (Patch' t h o) (Patch' t h' o) h h'
patchH_ f Patch {termEdits, typeEdits} = do
  newTermEdits <- termEdits & Map.traverseKeys . Referent.refs_ . Reference.h_ %%~ f
  newTypeEdits <- typeEdits & Map.traverseKeys . Reference.h_ %%~ f
  pure Patch {termEdits = newTermEdits, typeEdits = newTypeEdits}

patchO_ :: (Ord t, Ord o') => Traversal (Patch' t h o) (Patch' t h o') o o'
patchO_ f Patch {termEdits, typeEdits} = do
  newTermEdits <- termEdits & traversed . Set.traverse . TermEdit.h_ %%~ f
  newTypeEdits <- typeEdits & traversed . Set.traverse . TypeEdit.h_ %%~ f
  pure Patch {termEdits = newTermEdits, typeEdits = newTypeEdits}

trimap ::
  (Ord t', Ord h', Ord o') =>
  (t -> t') ->
  (h -> h') ->
  (o -> o') ->
  Patch' t h o ->
  Patch' t' h' o'
trimap ft fh fo (Patch tms tps) =
  Patch
    (Map.bimap (bimap (bimap ft fh) (bimap ft fh)) (Set.map (bimap ft fo)) tms)
    (Map.bimap (bimap ft fh) (Set.map (bimap ft fo)) tps)
