package local.bbsgeoimporter;
/** Extension point: resolvers add evidence, never infer ownership from a namespace. */
public interface ResourceAssociationResolver {
    void resolve(ResourceCatalog catalog);
}
